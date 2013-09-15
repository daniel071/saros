package de.fu_berlin.inf.dpp.net.internal.extensions;

import java.util.ArrayList;
import java.util.List;

import de.fu_berlin.inf.dpp.User;
import de.fu_berlin.inf.dpp.User.Permission;
import de.fu_berlin.inf.dpp.net.JID;

public class UserListExtension extends SarosSessionPacketExtension {

    public static final Provider PROVIDER = new Provider();

    private ArrayList<UserListEntry> userList = new ArrayList<UserListEntry>();

    public UserListExtension(String sessionID) {
        super(sessionID);
    }

    public void addUser(User user, long flags) {
        userList.add(UserListEntry.create(user, flags));
    }

    public List<UserListEntry> getEntries() {
        return userList;
    }

    public static class UserListEntry {
        public static final long USER_ADDED = 0x1L;
        public static final long USER_REMOVED = 0x2L;

        public long flags;
        public JID jid;
        public int colorID;
        public int favoriteColorID;
        public Permission permission;

        private static UserListEntry create(User user, long flags) {
            return new UserListEntry(user.getJID(), user.getColorID(),
                user.getFavoriteColorID(), user.getPermission(), flags);
        }

        private UserListEntry(JID jid, int colorID, int favoriteColorID,
            Permission permission, long flags) {
            this.jid = jid;
            this.colorID = colorID;
            this.favoriteColorID = favoriteColorID;
            this.permission = permission;
            this.flags = flags;
        }
    }

    public static class Provider extends
        SarosSessionPacketExtension.Provider<UserListExtension> {

        private Provider() {
            super("userList", UserListExtension.class);
        }
    }
}
