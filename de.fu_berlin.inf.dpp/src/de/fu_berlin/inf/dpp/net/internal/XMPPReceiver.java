package de.fu_berlin.inf.dpp.net.internal;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.PacketExtension;
import org.jivesoftware.smack.provider.PacketExtensionProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.xmlpull.mxp1.MXParser;
import org.xmlpull.v1.XmlPullParser;

import de.fu_berlin.inf.dpp.annotations.Component;
import de.fu_berlin.inf.dpp.net.IReceiver;
import de.fu_berlin.inf.dpp.net.IncomingTransferObject;
import de.fu_berlin.inf.dpp.net.IncomingTransferObject.IncomingTransferObjectExtensionProvider;
import de.fu_berlin.inf.dpp.net.SarosPacketCollector;
import de.fu_berlin.inf.dpp.net.SarosPacketCollector.CancelHook;
import de.fu_berlin.inf.dpp.net.business.DispatchThreadContext;

@Component(module = "net")
public class XMPPReceiver implements IReceiver {

    private static final Logger LOG = Logger.getLogger(XMPPReceiver.class);

    private IncomingTransferObjectExtensionProvider incomingExtProv;

    private DispatchThreadContext dispatchThreadContext;

    private Map<PacketListener, PacketFilter> listeners = Collections
        .synchronizedMap(new HashMap<PacketListener, PacketFilter>());

    private XmlPullParser parser;

    public XMPPReceiver(DispatchThreadContext dispatchThreadContext,
        IncomingTransferObjectExtensionProvider incomingExtProv) {

        this.dispatchThreadContext = dispatchThreadContext;
        this.incomingExtProv = incomingExtProv;
        this.parser = new MXParser();
    }

    @Override
    public void addPacketListener(PacketListener listener, PacketFilter filter) {
        listeners.put(listener, filter);
    }

    @Override
    public void removePacketListener(PacketListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void processPacket(final Packet packet) {
        dispatchThreadContext.executeAsDispatch(new Runnable() {
            @Override
            public void run() {
                forwardPacket(packet);
            }
        });
    }

    @Override
    public SarosPacketCollector createCollector(PacketFilter filter) {
        final SarosPacketCollector collector = new SarosPacketCollector(
            new CancelHook() {
                @Override
                public void cancelPacketCollector(SarosPacketCollector collector) {
                    removePacketListener(collector);
                }
            }, filter);
        addPacketListener(collector, filter);

        return collector;
    }

    @Override
    public void processTransferObject(
        final IncomingTransferObject transferObject) {

        dispatchThreadContext.executeAsDispatch(new Runnable() {

            @Override
            public void run() {

                // StreamServiceManager forward
                if (forwardTransferObject(transferObject))
                    return;

                Packet packet = convertTransferObjectToPacket(transferObject);

                if (packet != null)
                    forwardPacket(packet);
            }
        });
    }

    /**
     * Dispatches the packet to all registered listeners.
     * 
     * @sarosThread must be called from the Dispatch Thread
     */
    private void forwardPacket(Packet packet) {
        Map<PacketListener, PacketFilter> copy;

        synchronized (listeners) {
            copy = new HashMap<PacketListener, PacketFilter>(listeners);
        }
        for (Entry<PacketListener, PacketFilter> entry : copy.entrySet()) {
            PacketListener listener = entry.getKey();
            PacketFilter filter = entry.getValue();

            if (filter == null || filter.accept(packet)) {
                listener.processPacket(packet);
            }
        }
    }

    /**
     * Forwards the transfer object to all registered listeners by wrapping the
     * transfer object into a packet extension.
     * 
     * @return <code>true</code> if the transfer object was processed by a
     *         listener, <code>false</code> otherwise
     * 
     * @sarosThread must be called from the Dispatch Thread
     */
    /*
     * Note: left as a separate method because of different functionality.
     * Furthermore the next refactoring step is to incorporate an
     * IncomingTransferObject listener. It does not make sense to convert it to
     * a packet first.
     */
    private boolean forwardTransferObject(IncomingTransferObject transferObject) {
        Map<PacketListener, PacketFilter> copy;

        Packet packet = wrapTransferObject(transferObject);

        synchronized (listeners) {
            copy = new HashMap<PacketListener, PacketFilter>(listeners);
        }

        boolean processed = false;

        for (Entry<PacketListener, PacketFilter> entry : copy.entrySet()) {
            PacketListener listener = entry.getKey();
            PacketFilter filter = entry.getValue();

            if (filter == null || filter.accept(packet)) {
                listener.processPacket(packet);
                processed = true;
            }
        }

        return processed;
    }

    // FIXME doc: what kind of extension !
    /**
     * Creates a new packet that contains the transfer object as packet
     * extension.
     */
    private Packet wrapTransferObject(IncomingTransferObject transferObject) {

        TransferDescription description = transferObject
            .getTransferDescription();

        Packet packet = new Message();
        packet.setPacketID(Packet.ID_NOT_AVAILABLE);
        packet.setFrom(description.getSender().toString());
        packet.addExtension(incomingExtProv.create(transferObject));
        return packet;

    }

    /**
     * Deserializes the payload of an {@link IncomingTransferObject} back to its
     * original {@link PacketExtension} and returns a new packet containing the
     * deserialized packet extension.
     * 
     * This method is <b>not</b> thread safe and <b>must not</b> accessed by
     * multiple threads concurrently.
     */
    private Packet convertTransferObjectToPacket(
        IncomingTransferObject transferObject) {

        TransferDescription description = transferObject
            .getTransferDescription();

        String name = description.getType();
        String namespace = description.getNamespace();
        // IQ provider?

        PacketExtensionProvider provider = (PacketExtensionProvider) ProviderManager
            .getInstance().getExtensionProvider(name, namespace);

        if (provider == null) {
            LOG.warn("could not deserialize transfer object because no provider with namespace '"
                + namespace + "' and element name '" + name + "' is installed");
            return null;
        }

        PacketExtension extension = null;

        try {
            parser.setInput(
                new ByteArrayInputStream(transferObject.getPayload()), "UTF-8");
            /*
             * We have to skip the empty start tag because Smack expects a
             * parser that already has started parsing.
             */
            parser.next();
            extension = provider.parseExtension(parser);
        } catch (Exception e) {
            LOG.error(
                "could not deserialize transfer object payload: "
                    + e.getMessage(), e);

            // just to be safe
            parser = new MXParser();
            return null;
        }

        Packet packet = new Message();
        packet.setPacketID(description.getExtensionVersion());
        packet.setFrom(description.getSender().toString());
        packet.setTo(description.getRecipient().toString());
        packet.addExtension(extension);

        return packet;
    }
}
