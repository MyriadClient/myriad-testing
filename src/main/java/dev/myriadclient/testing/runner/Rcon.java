package dev.myriadclient.testing.runner;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * One long-lived RCON connection to the test server, for the commands tests set scenes up with and the server's view
 * they check against (a new login per command is what made the old suite slow). Reconnects once if the server closed
 * it. Thread-safe: commands go one at a time.
 */
public final class Rcon implements AutoCloseable {
	/** Packet types: log in, run a command; and one the server doesn't know, answered with an error line, as a marker. */
	private static final int AUTH = 3, COMMAND = 2, MARKER = 0;

	private final String host, password;
	private final int port;
	private Socket socket;
	private DataInputStream in;
	private OutputStream out;
	private int nextId = 1;

	public Rcon(String host, int port, String password) {
		this.host = host;
		this.port = port;
		this.password = password;
	}

	/** Runs {@code command} (no leading slash) and returns what the server answered. */
	public synchronized String run(String command) {
		try {
			return send(command);
		} catch (IOException first) {
			close();
			try {
				return send(command);
			} catch (IOException e) {
				throw new IllegalStateException("RCON to " + host + ":" + port + " failed: " + e.getMessage(), e);
			}
		}
	}

	private String send(String command) throws IOException {
		if (socket == null) connect();
		int id = nextId++, marker = nextId++;
		write(id, COMMAND, command);
		// The server splits a long answer into packets of 4096 bytes with the command's id, and answers in order: the
		// answer to a request after it says the command's is complete.
		write(marker, MARKER, "");
		return read(id, marker);
	}

	private void connect() throws IOException {
		socket = new Socket();
		socket.connect(new InetSocketAddress(host, port), 3000);
		socket.setSoTimeout(10000);
		in = new DataInputStream(socket.getInputStream());
		out = socket.getOutputStream();
		int id = nextId++;
		write(id, AUTH, password);
		int got = readPacket().id;
		if (got == -1) throw new IOException("wrong RCON password");
	}

	private void write(int id, int type, String body) throws IOException {
		byte[] text = body.getBytes(StandardCharsets.UTF_8);
		ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 + text.length + 2).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(4 + 4 + text.length + 2).putInt(id).putInt(type).put(text).put((byte) 0).put((byte) 0);
		out.write(b.array());
		out.flush();
	}

	private record Packet(int id, String body) {
	}

	private Packet readPacket() throws IOException {
		byte[] head = new byte[4];
		in.readFully(head);
		int length = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
		byte[] data = new byte[length];
		in.readFully(data);
		ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		int id = b.getInt();
		b.getInt();
		return new Packet(id, new String(data, 8, Math.max(0, length - 10), StandardCharsets.UTF_8));
	}

	private String read(int id, int marker) throws IOException {
		StringBuilder answer = new StringBuilder();
		for (Packet p = readPacket(); p.id != marker; p = readPacket()) {
			if (p.id == id) answer.append(p.body);
		}
		return answer.toString();
	}

	@Override
	public synchronized void close() {
		try {
			if (socket != null) socket.close();
		} catch (IOException ignored) {
			// Closing anyway.
		}
		socket = null;
	}
}
