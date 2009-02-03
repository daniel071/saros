package de.fu_berlin.inf.dpp.net.jingle;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.jivesoftware.smackx.jingle.JingleSession;
import org.jivesoftware.smackx.jingle.media.JingleMediaSession;
import org.jivesoftware.smackx.jingle.media.PayloadType;
import org.jivesoftware.smackx.jingle.nat.TransportCandidate;
import org.limewire.nio.NIODispatcher;
import org.limewire.rudp.DefaultRUDPContext;
import org.limewire.rudp.DefaultRUDPSettings;
import org.limewire.rudp.DefaultUDPService;
import org.limewire.rudp.RudpMessageDispatcher;
import org.limewire.rudp.UDPMultiplexor;
import org.limewire.rudp.UDPSelectorProvider;
import org.limewire.rudp.messages.RUDPMessageFactory;
import org.limewire.rudp.messages.impl.DefaultMessageFactory;

import de.fu_berlin.inf.dpp.util.Util;

/**
 * This class implements a file transfer session with jingle.
 * 
 * Jingle is a XMPP-extension with id XEP-0166. Documentation can be found at
 * http://xmpp.org/extensions/xep-0166.html .
 * 
 * This implementation uses TCP as transport protocol which fall back to UDP
 * when a TCP connection failed. To ensure no data loss when transmitting with
 * UDP the RUDP implementation from the Limewire project are used.
 * 
 * Documentation for the RUDP component from limewire can be found at:
 * http://wiki.limewire.org/index.php?title=Javadocs .
 * 
 * @author chjacob
 * @author oezbek
 */
public class JingleFileTransferSession extends JingleMediaSession {

    public static final int TIMEOUTSECONDS = 15;

    private class ReceiverThread extends Thread {

        private ObjectInputStream input;

        public ReceiverThread(ObjectInputStream ii) {
            this.input = ii;
        }

        public void run() {

            while (!isInterrupted()) {
                JingleFileTransferData data;
                try {
                    data = (JingleFileTransferData) input.readObject();
                } catch (IOException e) {
                    return;
                } catch (ClassNotFoundException e) {
                    logger.error("Received unexpected object in ReceiveThread",
                            e);
                    continue;
                }
                switch (data.type) {
                case FILELIST_TRANSFER:
                    logger
                            .debug("Received FileList: "
                                    + data.file_list_content);
                    for (IJingleFileTransferListener listener : listeners) {
                        listener.incomingFileList(data.file_list_content,
                                data.sender);
                    }
                    break;
                case RESOURCE_TRANSFER:
                    logger
                            .debug("Received Resource: "
                                    + data.file_project_path);
                    for (IJingleFileTransferListener listener : listeners) {
                        listener.incomingResourceFile(data,
                                new ByteArrayInputStream(data.content));
                    }
                }
            }

        }
    }

    private static Logger logger = Logger
            .getLogger(JingleFileTransferSession.class);

    private ReceiverThread receiveThread;
    private Set<IJingleFileTransferListener> listeners;
    private UDPSelectorProvider udpSelectorProvider;

    private String connectionType = null;
    private Socket socket;
    private ObjectOutputStream objectOutputStream;
    private ObjectInputStream objectInputStream;

    private String remoteIp;
    private String localIp;
    private int localPort;
    private int remotePort;

    /**
     * Create AND initialize a new JingleFileTransferSession. This includes
     * connecting to given remote side (if initiator).
     * 
     * @param payloadType
     *            Our PayloadType, which identifies which packets we handle.
     *            Smack takes care of this.
     * @param remote
     *            The remote IP and port suggested by Jingle to us.
     * @param local
     *            Our own IP and port suggested by Jingle to us.
     * @param mediaLocator
     *            MediaLocator to pass to super()
     * @param jingleSession
     *            maybenull, the existing JingleSession.
     * @param transferData
     *            The data to transfer once the setTransmit method is called.
     * @param listeners
     *            We will notify these listeners if we receive files from the
     *            remote side.
     */
    public JingleFileTransferSession(PayloadType payloadType,
            TransportCandidate remote, TransportCandidate local,
            String mediaLocator, JingleSession jingleSession,
            Set<IJingleFileTransferListener> listeners) {
        super(payloadType, remote, local, mediaLocator, jingleSession);

        this.listeners = listeners;

        initialize();
    }

    /**
     * Initialization of the session. It tries to create sockets for both, TCP
     * and UDP. The UDP Socket is a reliable implementation from the Limewire
     * project. Documentation can be found at http://wiki.limewire.org.
     */
    @Override
    public void initialize() {

        if (this.getLocal().getSymmetric() != null) {

            localIp = this.getLocal().getLocalIp();
            localPort = Util.getFreePort();

            remoteIp = this.getLocal().getIp();
            remotePort = this.getLocal().getSymmetric().getPort();

            // TODO what does symmetric mean
            logger.info("Created symmetric - local: " + localIp + ":"
                    + localPort + " -> remote: " + remoteIp + ":" + remotePort);

        } else {
            localIp = this.getLocal().getLocalIp();
            localPort = this.getLocal().getPort();

            remoteIp = this.getRemote().getIp();
            remotePort = this.getRemote().getPort();

            logger
                    .info("Created asymmetric - local: " + localIp + ":"
                            + localPort + " <-> remote: " + remoteIp + ":"
                            + remotePort);
        }

        // create RUDP service
        RudpMessageDispatcher dispatcher = new RudpMessageDispatcher();
        DefaultUDPService service = new DefaultUDPService(dispatcher);
        RUDPMessageFactory factory = new DefaultMessageFactory();
        udpSelectorProvider = new UDPSelectorProvider(new DefaultRUDPContext(
                factory, NIODispatcher.instance().getTransportListener(),
                service, new DefaultRUDPSettings()));
        UDPMultiplexor udpMultiplexor = udpSelectorProvider.openSelector();
        dispatcher.setUDPMultiplexor(udpMultiplexor);
        NIODispatcher.instance().registerSelector(udpMultiplexor,
                udpSelectorProvider.getUDPSocketChannelClass());
        try {
            service.start(localPort);
        } catch (IOException e) {
            logger.debug("Failed to create RUDP service");
        }

        if (getJingleSession().getInitiator().equals(
                getJingleSession().getConnection().getUser())) {

            initializeAsServer();
        } else {
            initializeAsClient();
        }

        if (connectionType != null) {
            logger.debug("Jingle Connection established using "
                    + connectionType);
        } else {
            logger.debug("Could not establish connection using Jingle");
        }

    }

    private void initializeAsClient() {

        ArrayList<SocketCreator> creators = new ArrayList<SocketCreator>(2);

        creators.add(SocketCreator.getWrapped("TCP", 
                Util.retryEvery500ms(new Callable<Socket>() {
                    public Socket call() throws Exception {
                        return new Socket(remoteIp, remotePort);
                    }
                })));

        creators.add(SocketCreator.getWrapped("UDP", 
                Util.delay(7500,               
                Util.retryEvery500ms(new Callable<Socket>() {
                    public Socket call() throws Exception {

                        Socket usock = udpSelectorProvider.openSocketChannel()
                                .socket();
                        usock.setSoTimeout(0);
                        usock.setKeepAlive(true);
                        usock.connect(new InetSocketAddress(InetAddress
                                .getByName(remoteIp), remotePort));
                        return usock;
                    }
                }))));

        connect(creators);
    }

    protected void initializeAsServer() {

        ArrayList<SocketCreator> creators = new ArrayList<SocketCreator>(2);

        creators.add(new SocketCreator("TCP") {

            public Socket call() throws Exception {

                ServerSocket serverSocket = new ServerSocket(localPort);
                serverSocket.setSoTimeout(0);

                return serverSocket.accept();
            }
        });

        creators.add(new SocketCreator("UDP") {

            public Socket call() throws Exception {
                Socket usock = udpSelectorProvider.openAcceptorSocketChannel()
                        .socket();
                usock.setSoTimeout(0);
                usock.connect(new InetSocketAddress(InetAddress
                        .getByName(remoteIp), remotePort));
                usock.setKeepAlive(true);

                return usock;
            }
        });

        connect(creators);
    }

    abstract static class SocketCreator implements Callable<Socket> {

        SocketCreator(String type) {
            this.type = type;
        }

        String type;

        public String getType() {
            return this.type;
        }

        public static SocketCreator getWrapped(String type,
                final Callable<Socket> callable) {
            return new SocketCreator(type) {
                public Socket call() throws Exception {
                    return callable.call();
                }
            };
        }
    }

    private void connect(Collection<SocketCreator> connects) {

        ExecutorCompletionService<Socket> completionService = new ExecutorCompletionService<Socket>(
                Executors.newFixedThreadPool(connects.size()));

        Map<Future<Socket>, SocketCreator> futures = new HashMap<Future<Socket>, SocketCreator>();

        for (SocketCreator creator : connects) {
            futures.put(completionService.submit(creator), creator);
        }

        for (int i = 0; i < connects.size(); i++) {

            Future<Socket> socketFuture = null;
            try {
                socketFuture = completionService.poll(TIMEOUTSECONDS,
                        TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                JingleFileTransferSession.logger.error(
                        "Unexpected interrupted exception in startTrasmit", e);
            }

            if (socketFuture == null) {
                JingleFileTransferSession.logger
                        .debug("Could not connect with either TCP or UDP.");
                break;
            }

            try {
                this.socket = socketFuture.get();
            } catch (InterruptedException e) {
                JingleFileTransferSession.logger.error(
                        "Unexpected interrupted exception in startTrasmit", e);
            } catch (ExecutionException e) {
                JingleFileTransferSession.logger
                        .debug("Failed to listen with either TCP or UDP.");
                continue;
            }

            try {
                this.objectOutputStream = new ObjectOutputStream(socket
                        .getOutputStream());
                this.objectInputStream = new ObjectInputStream(socket
                        .getInputStream());

                this.receiveThread = new ReceiverThread(objectInputStream);
                this.receiveThread.start();

                this.connectionType = futures.get(socketFuture).getType();

                for (IJingleFileTransferListener listener : listeners) {
                    listener.connected(this.connectionType, remoteIp);
                }

                // Make sure the other connect-futures are canceled
                for (Future<Socket> future : futures.keySet()) {
                    future.cancel(true);
                }

                return;
            } catch (IOException e) {
                JingleFileTransferSession.logger
                        .debug("Failed to listen with either TCP or UDP.");

                close();
            }
        }

        // Timeout, so cancel all
        for (Future<Socket> future : futures.keySet()) {
            future.cancel(true);
        }

        assert objectOutputStream == null && objectInputStream == null;
    }

    /**
     * This method is called from the JingleFileTransferManager to send files
     * with this session.
     * 
     * @throws JingleSessionException
     *             if sending failed.
     */
    public synchronized void send(JingleFileTransferData transferData)
            throws JingleSessionException {

        if (objectOutputStream != null) {
            try {
                objectOutputStream.writeObject(transferData);
                objectOutputStream.flush();
                logger.debug("Sent: " + transferData);
                return;
            } catch (IOException e) {
                throw new JingleSessionException(
                        "Failed to send files with Jingle");
            }
        }

        throw new JingleSessionException("Failed to send files with Jingle");
    }

    /**
     * This method is called from Jingle AFTER a jingle session is established.
     * We thus could start sending here, but we want that others call us using
     * send.
     */
    @Override
    public void startTrasmit() {
        // Do nothing -> Users should call send(...) directly
    }

    @Override
    public void stopTrasmit() {
        // Do nothing -> Users should call send(...) directly
    }

    @Override
    public void setTrasmit(boolean active) {
        logger.error("Unexpected call to setTrasmit(active ==" + active + ")");
    }

    /**
     * This method is called from Jingle AFTER a jingle session is established.
     * Since we want others to call us, we need to be ready for transmitting
     * before this
     */
    @Override
    public void startReceive() {
        // do nothing.
    }

    @Override
    public void stopReceive() {
        close();
    }

    public void close() {

        if (receiveThread != null)
            receiveThread.interrupt();

        Util.close(objectInputStream);
        Util.close(objectOutputStream);
        Util.close(socket);

        objectInputStream = null;
        objectOutputStream = null;
        socket = null;

        connectionType = null;
    }
}
