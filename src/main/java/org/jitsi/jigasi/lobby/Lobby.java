/*
 * Jigasi, the JItsi GAteway to SIP.
 *
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jigasi.lobby;

import java.util.concurrent.*;

import net.java.sip.communicator.service.protocol.event.*;
import org.jitsi.jigasi.*;
import org.jivesoftware.smackx.nick.packet.*;

import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;
import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;
import org.jxmpp.jid.impl.*;

/**
 * Class used to join and leave the lobby room and provides a way to handle lobby events.
 * If lobby is enabled JvbConference will fail join registration and Lobby will be used
 * to confirm join in the initial JvbConference.
 *
 * @author Cristian Florin Ghita
 */
public class Lobby
        implements ChatRoomInvitationListener, LocalUserChatRoomPresenceListener
{
    /**
     * The logger.
     */
    private final static Logger logger = Logger.getLogger(Lobby.class);

    /**
     * The XMPP provider used to join JVB conference.
     */
    private final ProtocolProviderService xmppProvider;

    /**
     * Room full Jid.
     */
    private final EntityFullJid roomJid;

    /**
     * Main room Jid.
     */
    private final Jid mainRoomJid;

    /**
     * Helper call context.
     */
    private final CallContext callContext;

    /**
     * <tt>ChatRoom</tt> instance that hosts the conference(not null if joined).
     */
    private ChatRoom mucRoom = null;

    /**
     * <tt>JvbConference</tt> Handles JVB conference events and connections.
     */
    private JvbConference jvbConference = null;

    /**
     * <tt>SipGatewaySession</tt> Handles SIP events and connections.
     */
    private SipGatewaySession sipGatewaySession = null;

    /**
     * Creates a new instance of <tt>Lobby</tt>
     *
     * @param protocolProviderService <tt>ProtocolProviderService</tt> registered protocol service to be used.
     * @param context <tt>CallContext</tt> to be used.
     * @param jid <tt>EntityFullJid</tt> for the lobby room to join.
     */
    public Lobby(ProtocolProviderService protocolProviderService,
                 CallContext context,
                 EntityFullJid lobbyJid,
                 Jid roomJid,
                 JvbConference jvbConference,
                 SipGatewaySession sipGateway)
    {
        super();

        this.xmppProvider = protocolProviderService;

        this.roomJid = lobbyJid;

        this.callContext = context;

        this.mainRoomJid = roomJid;

        this.jvbConference = jvbConference;

        this.sipGatewaySession = sipGateway;
    }

    /**
     * Used to join the lobby room.
     *
     * @throws OperationFailedException
     * @throws OperationNotSupportedException
     */
    public void join() throws OperationFailedException, OperationNotSupportedException
    {
        joinRoom(getRoomJid());

        this.sipGatewaySession
                .getSoundNotificationManager()
                .notifyLobbyWaitReview();
    }

    /**
     * Called by join can be overridden.
     *
     * @param roomJid The lobby room jid to use to join.
     * @throws OperationFailedException
     * @throws OperationNotSupportedException
     */
    protected void joinRoom(Jid roomJid) throws OperationFailedException, OperationNotSupportedException
    {
        OperationSetMultiUserChat muc
                = this.xmppProvider.getOperationSet(OperationSetMultiUserChat.class);

        muc.addInvitationListener(this);

        muc.addPresenceListener(this);

        ProtocolProviderService pps = getProtocolProvider();

        ChatRoom mucRoom = muc.findRoom(roomJid.toString());

        setupChatRoom(mucRoom);

        Localpart resourceIdentifier = getResourceIdentifier();

        mucRoom.joinAs(resourceIdentifier.toString());

        this.mucRoom = mucRoom;
    }

    /**
     * Used to leave the lobby room.
     */
    public void leave()
    {
        leaveRoom();
    }

    /**
     * Called by leave can be overridden.
     */
    protected void leaveRoom()
    {
        OperationSetMultiUserChat muc
                = this.xmppProvider.getOperationSet(OperationSetMultiUserChat.class);

        muc.removeInvitationListener(this);

        if (mucRoom == null)
        {
            logger.warn(getCallContext() + " MUC room is null");
            return;
        }

        ProtocolProviderService pps = getProtocolProvider();

        muc.removePresenceListener(this);

        mucRoom.leave();

        mucRoom = null;
    }

    /**
     * Used to get <tt>ChatRoomInvitationListener</tt> events. After participant is allowed to join this method will
     * be called.
     *
     * @param chatRoomInvitationReceivedEvent <tt>ChatRoomInvitationReceivedEvent</tt> contains invitation info.
     */
    @Override
    public void invitationReceived(ChatRoomInvitationReceivedEvent chatRoomInvitationReceivedEvent)
    {
        try
        {
            this.sipGatewaySession
                    .getSoundNotificationManager()
                    .notifyLobbyAccessGranted();

            if (this.jvbConference != null)
            {
                this.jvbConference.joinConferenceRoom();
            }
            else
            {
                logger.error("No JVB conference!!!");
            }

            leave();
        }
        catch (Exception ex)
        {
            logger.error(ex.toString());
        }
    }

    /**
     * Participant is kicked if rejected on join and this method handles the lobby rejection and lobby room destruction.
     * Participant receives LOCAL_USER_LEFT if lobby is disabled.
     *
     * @param localUserChatRoomPresenceChangeEvent <tt>LocalUserChatRoomPresenceChangeEvent</tt> contains reason.
     */
    @Override
    public void localUserPresenceChanged(LocalUserChatRoomPresenceChangeEvent localUserChatRoomPresenceChangeEvent)
    {
        try
        {
            if (localUserChatRoomPresenceChangeEvent.getChatRoom().equals(this.mucRoom))
            {
                if (localUserChatRoomPresenceChangeEvent.getEventType()
                        == LocalUserChatRoomPresenceChangeEvent.LOCAL_USER_KICKED)
                {
                    /**
                     * Lobby access denied.
                     */
                    this.sipGatewaySession
                            .getSoundNotificationManager()
                            .notifyLobbyAccessDenied();

                    leave();
                }

                if (localUserChatRoomPresenceChangeEvent.getEventType()
                        == LocalUserChatRoomPresenceChangeEvent.LOCAL_USER_LEFT)
                {
                    /**
                     * Lobby access granted.
                     */

                    String alternateAddress = localUserChatRoomPresenceChangeEvent.getAlternateAddress();

                    if (alternateAddress == null)
                    {
                        return;
                    }
                    else
                    {
                        Jid alternateJid = (Jid)JidCreate.entityBareFrom(alternateAddress);

                        if (alternateJid.equals(this.mainRoomJid) == false)
                        {
                            logger.warn("Alternate Jid not the same as main room Jid!");
                        }

                        /**
                         * The left event is used here in case the lobby is disabled.
                         */
                        if (this.jvbConference != null)
                        {
                            this.jvbConference.joinConferenceRoom();
                        }
                        else
                        {
                            logger.error("No JVB conference!!!");
                        }
                    }
                }

                if (localUserChatRoomPresenceChangeEvent.getEventType()
                        == LocalUserChatRoomPresenceChangeEvent.LOCAL_USER_JOINED)
                {
                    /**
                     * After lobby is joined playback the waiting notification.
                     * Event is not working at the moment.
                     */
                }

                if (localUserChatRoomPresenceChangeEvent.getEventType()
                        == LocalUserChatRoomPresenceChangeEvent.LOCAL_USER_JOIN_FAILED)
                {
                    /**
                     * If join has failed playback the meeting ended notification.
                     */

                    logger.error("Failed to join lobby!");
                }

                if (localUserChatRoomPresenceChangeEvent.getEventType()
                        == LocalUserChatRoomPresenceChangeEvent.LOCAL_USER_ROOM_DESTROYED)
                {
                    this.sipGatewaySession
                            .getSoundNotificationManager()
                            .notifyLobbyRoomDestroyed();
                }
            }
        }
        catch (Exception ex)
        {
            logger.error(ex.toString());
        }
    }

    /**
     * Holds call information.
     *
     * @return <tt>CallContext</tt>
     */
    public CallContext getCallContext()
    {
        return this.callContext;
    }

    /**
     * Gets the lobby jid.
     *
     * @return <tt>Jid</tt>
     */
    public Jid getRoomJid()
    {
        return this.roomJid;
    }

    /**
     * Returns the used protocol provider.
     *
     * @return <tt>ProtocolProviderService</tt> registered protocol provider.
     */
    public ProtocolProviderService getProtocolProvider()
    {
        return this.xmppProvider;
    }

    /**
     * Used to which joined.
     *
     * @return <tt>Localpart</tt> identifier.
     */
    public Localpart getResourceIdentifier()
    {
        return this.roomJid.getLocalpartOrNull();
    }

    /**
     * Override this to setup the lobby room before join.
     *
     * @param mucRoom <tt>ChatRoom</tt> lobby to join.
     */
    void setupChatRoom(ChatRoom mucRoom)
    {
        if (mucRoom instanceof ChatRoomJabberImpl)
        {
            String displayName = this.sipGatewaySession.getMucDisplayName();
            if (displayName != null)
            {
                ((ChatRoomJabberImpl)mucRoom).addPresencePacketExtensions(
                        new Nick(displayName));
            }
            else
            {
                logger.error(this.callContext
                        + " No display name to use...");
            }
        }
    }
}
