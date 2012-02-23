package com.xebialabs.overthere.ssh;

import com.google.common.io.Closeables;
import com.google.common.util.concurrent.Monitor;
import com.xebialabs.overthere.*;
import com.xebialabs.overthere.spi.AddressPortResolver;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static com.xebialabs.overthere.ssh.SshConnectionBuilder.PORT_ALLOCATION_RANGE_START;
import static com.xebialabs.overthere.ssh.SshConnectionBuilder.PORT_ALLOCATION_RANGE_START_DEFAULT;
import static com.xebialabs.overthere.util.Sockets.checkAvailable;
import static com.xebialabs.overthere.util.Sockets.getServerSocket;
import static java.lang.String.format;
import static java.net.InetSocketAddress.createUnresolved;

/**
 * A connection to a 'jump station' host using SSH w/ local port forwards.
 */
public class SshTunnelConnection extends SshConnection implements AddressPortResolver {

	private static final Monitor M = new Monitor();
	private static final int MAX_PORT = 65536;

	private Map<InetSocketAddress, Integer> localPortForwards = newHashMap();
	
	private List<PortForwarder> portForwarders = newArrayList();

	private Integer startPortRange;

	public SshTunnelConnection(final String protocol, final ConnectionOptions options, AddressPortResolver resolver) {
		super(protocol, options, resolver);
		this.startPortRange = options.get(PORT_ALLOCATION_RANGE_START, PORT_ALLOCATION_RANGE_START_DEFAULT);
	}

	@Override
	protected void connect() {
		super.connect();
		checkState(sshClient != null, "Should have set an SSH client when connected");
	}

	@Override
	public void doClose() {
		logger.debug("Closing tunnel.");
		for (PortForwarder portForwarder : portForwarders) {
			Closeables.closeQuietly(portForwarder);
		}

		super.doClose();
	}

	@Override
	public InetSocketAddress resolve(InetSocketAddress address) {
		M.enter();
		try {
			if (localPortForwards.containsKey(address)) {
				return createUnresolved("localhost", localPortForwards.get(address));
			}

			Integer localPort = findFreePort();
			try {
				portForwarders.add(startForwarder(address, localPort));
			} catch (IOException e) {
				Closeables.closeQuietly(this);
				throw new RuntimeIOException(e);
			}
			return createUnresolved("localhost", localPort);
		} finally {
			M.leave();
		}
	}

	private Integer findFreePort() {
		for (int port = startPortRange; port < MAX_PORT; port++) {
			if (checkAvailable(port)) {
				return port;
			}
		}
		throw new IllegalStateException("Could not find a single free port in the range 1025-65535...");
	}

	private PortForwarder startForwarder(InetSocketAddress remoteAddress, Integer localPort) throws IOException {
		PortForwarder forwarderThread = new PortForwarder(sshClient, remoteAddress, localPort);
		logger.info("Starting {}", forwarderThread.getName());
		forwarderThread.start();
		try {
			forwarderThread.latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return forwarderThread;
	}

	@Override
	protected OverthereFile getFile(String hostPath, boolean isTempFile) throws RuntimeIOException {
		throw new UnsupportedOperationException("Cannot get a file from the tunnel.");
	}

	@Override
	protected OverthereFile getFile(OverthereFile parent, String child, boolean isTempFile) throws RuntimeIOException {
		throw new UnsupportedOperationException("Cannot get a file from the tunnel.");
	}

	@Override
	public OverthereProcess startProcess(CmdLine commandLine) {
		throw new UnsupportedOperationException("Cannot start a process on the tunnel.");
	}

	@Override
	protected CmdLine processCommandLine(CmdLine commandLine) {
		throw new UnsupportedOperationException("Cannot process a command line for the tunnel.");
	}

	@Override
	protected void addCommandSeparator(CmdLine commandLine) {
		throw new UnsupportedOperationException("Cannot add a separator to the command line in the tunnel.");
	}

	@Override
	protected SshProcess createProcess(Session session, CmdLine commandLine) throws TransportException, ConnectionException {
		throw new UnsupportedOperationException("Cannot create a process in the tunnel.");
	}

	@Override
	public void setWorkingDirectory(OverthereFile workingDirectory) {
		throw new UnsupportedOperationException("Cannot set a working directory on the tunnel.");
	}

	@Override
	public OverthereFile getWorkingDirectory() {
		throw new UnsupportedOperationException("Cannot get a working directory from the tunnel.");
	}

	@Override
	public int execute(OverthereProcessOutputHandler handler, CmdLine commandLine) {
		throw new UnsupportedOperationException("Cannot execute a command on the tunnel.");
	}

	private static final Logger logger = LoggerFactory.getLogger(SshTunnelConnection.class);

	private static class PortForwarder extends Thread implements Closeable {
		private final SSHClient sshClient;
		private final InetSocketAddress remoteAddress;
		private final Integer localPort;
		private ServerSocket ss;
		private CountDownLatch latch = new CountDownLatch(1);

		public PortForwarder(SSHClient sshClient, InetSocketAddress remoteAddress, Integer localPort) {
			super(buildName(remoteAddress, localPort));
			this.sshClient = sshClient;
			this.remoteAddress = remoteAddress;
			this.localPort = localPort;
		}

		private static String buildName(InetSocketAddress remoteAddress, Integer localPort) {
			return format("SSH local port forward thread [%d:%s]", localPort, remoteAddress.toString());
		}

		@Override
		public void run() {
			LocalPortForwarder.Parameters params = new LocalPortForwarder.Parameters("localhost", localPort, remoteAddress.getHostName(), remoteAddress.getPort());
			try {
				ss = getServerSocket(localPort);
			} catch (IOException ioe) {
				logger.error(format("Couldn't setup local port forward [%d:%s]", localPort, remoteAddress.toString()), ioe);
				throw new RuntimeIOException(ioe);
			}

			LocalPortForwarder forwarder = sshClient.newLocalPortForwarder(params, ss);
			try {
				latch.countDown();
				forwarder.listen();
			} catch (IOException ignore) {
				// OK.
			}
		}
		
		private static final Logger logger = LoggerFactory.getLogger(PortForwarder.class);

		@Override
		public void close() throws IOException {
			ss.close();
			try {
				this.join();
			} catch (InterruptedException e) {
				// OK.
			}
		}
	}
}
