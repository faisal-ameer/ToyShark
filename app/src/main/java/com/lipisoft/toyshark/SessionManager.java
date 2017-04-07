/*
 *  Copyright 2014 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package com.lipisoft.toyshark;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.lipisoft.toyshark.network.ip.IPv4Header;
import com.lipisoft.toyshark.socket.DataConst;
import com.lipisoft.toyshark.socket.SocketNIODataService;
import com.lipisoft.toyshark.socket.SocketProtector;
import com.lipisoft.toyshark.transport.tcp.TCPHeader;
import com.lipisoft.toyshark.transport.udp.UDPHeader;
import com.lipisoft.toyshark.util.PacketUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manage in-memory storage for VPN client session.
 * @author Borey Sao
 * Date: May 20, 2014
 */
public class SessionManager {
	private static final String TAG = "SessionManager";
	private static final Object syncObj = new Object();
	private static volatile SessionManager instance;
	private Map<String, Session> table;
	private SocketProtector protector;
	private Selector selector;

	private SessionManager() {
		table = new ConcurrentHashMap<>();
		protector = SocketProtector.getInstance();
		try {
			selector = Selector.open();
		} catch (IOException e) {
			Log.e(TAG,"Failed to create Socket Selector");
		}
	}

	public static SessionManager getInstance(){
		if(instance == null){
			synchronized(syncObj){
				if(instance == null)
					instance = new SessionManager();
			}
		}
		return instance;
	}

	public Selector getSelector(){
		return selector;
	}

	/**
	 * keep java garbage collector from collecting a session
	 * @param session Session
	 */
	void keepSessionAlive(Session session) {
		if(session != null){
			String key = createKey(session.getDestIp(), session.getDestPort(),
					session.getSourceIp(), session.getSourcePort());
			table.put(key, session);
		}
	}

//	public Iterator<Session> getAllSession(){
//		return table.values().iterator();
//	}

	int addClientUDPData(IPv4Header ip, UDPHeader udp, byte[] buffer, Session session){
		int start = ip.getIPHeaderLength() + 8;
		int len = udp.getLength() - 8; //exclude header size
		if(len < 1)
			return 0;

		if((buffer.length - start) < len)
			len = buffer.length - start;

		byte[] data = new byte[len];
		System.arraycopy(buffer, start, data, 0, len);
		session.setSendingData(data);

		return len;
	}

	/**
	 * add data from client which will be sending to the destination server later one when receiving PSH flag.
	 * @param ip IP Header
	 * @param tcp TCP Header
	 * @param buffer Data
	 */
	int addClientData(IPv4Header ip, TCPHeader tcp, byte[] buffer){
		Session session = getSession(ip.getDestinationIP(), tcp.getDestinationPort(), ip.getSourceIP(), tcp.getSourcePort());
		if(session == null)
			return 0;

		//check for duplicate data
		if(session.getRecSequence() != 0 && tcp.getSequenceNumber() < session.getRecSequence()){
			return 0;
		}
		int start = ip.getIPHeaderLength() + tcp.getTCPHeaderLength();
		int len = buffer.length - start;
		byte[] data = new byte[len];
		System.arraycopy(buffer, start, data, 0, len);
		//appending data to buffer
		session.setSendingData(data);
		return len;
	}

	Session getSession(int ip, int port, int srcIp, int srcPort) {
		String key = createKey(ip, port, srcIp, srcPort);

		return getSessionByKey(key);
	}

	@Nullable public Session getSessionByKey(String key) {
		if(table.containsKey(key)){
			return table.get(key);
		}

		return null;
	}

	public Session getSessionByChannel(AbstractSelectableChannel channel) {
		Collection<Session> sessions = table.values();

		for (Session session: sessions) {
			AbstractSelectableChannel abstractSelectableChannel = session.getChannel();
			if (abstractSelectableChannel instanceof SocketChannel) {
				SocketChannel socketChannel = (SocketChannel) abstractSelectableChannel;
				if (socketChannel == channel) {
					return session;
				}
			} else if (abstractSelectableChannel instanceof DatagramChannel) {
				DatagramChannel datagramChannel = (DatagramChannel) abstractSelectableChannel;
				if (datagramChannel == channel) {
					return session;
				}
			}
		}

		return null;
	}

//	public void removeSessionByChannel(SocketChannel channel){
//		synchronized (syncTable) {
//			Set<String> keys = table.keySet();
//			for (String key: keys) {
//				Session session = table.get(key);
//				if(session != null && session.getSocketChannel() == channel) {
//					table.remove(key);
//					Log.d(TAG, "closed session -> " + key);
//				}
//			}
//		}
//	}

	/**
	 * remove session from memory, then close socket connection.
	 * @param ip Destination IP Address
	 * @param port Destination Port
	 * @param srcIp Source IP Address
	 * @param srcPort Source Port
	 */
	void closeSession(int ip, int port, int srcIp, int srcPort){
		String key = createKey(ip, port, srcIp, srcPort);
		Session session = table.remove(key);

		if(session != null){
			final AbstractSelectableChannel channel = session.getChannel();
			try {
				if (channel != null) {
					channel.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			Log.d(TAG,"closed session -> " + key);
		}
	}

	public void closeSession(@NonNull Session session){
		String key = SessionManager.createKey(session.getDestIp(),
				session.getDestPort(), session.getSourceIp(),
				session.getSourcePort());
		table.remove(key);

		try {
			AbstractSelectableChannel channel = session.getChannel();
			if(channel != null) {
				channel.close();
			}
		} catch (IOException e) {
			Log.e(TAG, e.toString());
		}
		Log.d(TAG,"closed session -> " + key);
	}

	@Nullable Session createNewUDPSession(int ip, int port, int srcIp, int srcPort){
		String keys = createKey(ip, port, srcIp, srcPort);

		if (table.containsKey(keys))
			return table.get(keys);

		Session session = new Session(srcIp, srcPort, ip, port);

		DatagramChannel channel;

		try {
			channel = DatagramChannel.open();
			channel.socket().setSoTimeout(0);
			channel.configureBlocking(false);

		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		protector.protect(channel.socket());

		//initiate connection to reduce latency
		String ips = PacketUtil.intToIPAddress(ip);
		String sourceIpAddress = PacketUtil.intToIPAddress(srcIp);
		SocketAddress socketAddress = new InetSocketAddress(ips, port);
		Log.d(TAG,"initialized connection to remote UDP server: " + ips + ":" +
				port + " from " + sourceIpAddress + ":" + srcPort);

		try {
			channel.connect(socketAddress);
			session.setConnected(channel.isConnected());
		} catch(IOException e) {
			e.printStackTrace();
			return null;
		}

		try {
			synchronized(SocketNIODataService.syncSelector2) {
				selector.wakeup();
				synchronized(SocketNIODataService.syncSelector) {
					SelectionKey selectionKey;
					if (channel.isConnected()) {
						selectionKey = channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
					} else {
						selectionKey = channel.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ |
								SelectionKey.OP_WRITE);
					}
					session.setSelectionKey(selectionKey);
					Log.d(TAG,"Registered udp selector successfully");
				}
			}
		} catch (ClosedChannelException e) {
			e.printStackTrace();
			Log.e(TAG,"failed to register udp channel with selector: "+ e.getMessage());
			return null;
		}

		session.setChannel(channel);

		if (table.containsKey(keys)) {
			try {
				channel.close();
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}
		} else {
			table.put(keys, session);
		}
		Log.d(TAG,"new UDP session successfully created.");
		return session;
	}

	@Nullable Session createNewSession(int ip, int port, int srcIp, int srcPort){
		String key = createKey(ip, port, srcIp, srcPort);
		if (table.containsKey(key)) {
			Log.e(TAG, "Session was already created.");
			return null;
		}

		Session session = new Session(srcIp, srcPort, ip, port);

		SocketChannel channel;
		try {
			channel = SocketChannel.open();
			channel.socket().setKeepAlive(true);
			channel.socket().setTcpNoDelay(true);
			channel.socket().setSoTimeout(0);
			channel.socket().setReceiveBufferSize(DataConst.MAX_RECEIVE_BUFFER_SIZE);
			channel.configureBlocking(false);
		}catch(SocketException e){
			Log.e(TAG, e.toString());
			return null;
		} catch (IOException e) {
			Log.e(TAG,"Failed to create SocketChannel: "+ e.getMessage());
			return null;
		}
		String ips = PacketUtil.intToIPAddress(ip);
		Log.d(TAG,"created new SocketChannel for " + key);

		protector.protect(channel.socket());

		Log.d(TAG,"Protected new SocketChannel");

		//initiate connection to reduce latency
		SocketAddress socketAddress = new InetSocketAddress(ips, port);
		Log.d(TAG,"initiate connecting to remote tcp server: " + ips + ":" + port);
		boolean connected;
		try{
			connected = channel.connect(socketAddress);
		} catch(IOException e) {
			Log.e(TAG, e.toString());
			return null;
		}

		session.setConnected(connected);

		//register for non-blocking operation
		try {
			synchronized(SocketNIODataService.syncSelector2){
				selector.wakeup();
				synchronized(SocketNIODataService.syncSelector){
					SelectionKey selectionKey = channel.register(selector,
							SelectionKey.OP_CONNECT | SelectionKey.OP_READ |
									SelectionKey.OP_WRITE);
					session.setSelectionKey(selectionKey);
					Log.d(TAG,"Registered tcp selector successfully");
				}
			}
		} catch (ClosedChannelException e) {
			e.printStackTrace();
			Log.e(TAG,"failed to register tcp channel with selector: " + e.getMessage());
			return null;
		}

		session.setChannel(channel);

		if (table.containsKey(key)) {
			try {
				channel.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return null;
		} else {
			table.put(key, session);
		}
		return session;
	}
	/**
	 * create session key based on destination ip+port and source ip+port
	 * @param ip Destination IP Address
	 * @param port Destination Port
	 * @param srcIp Source IP Address
	 * @param srcPort Source Port
	 * @return String
	 */
	public static String createKey(int ip, int port, int srcIp, int srcPort){
		return PacketUtil.intToIPAddress(srcIp) + ":" + srcPort + "-" +
				PacketUtil.intToIPAddress(ip) + ":" + port;
	}
}
