package com.wizardhax;

import com.google.common.base.Joiner;
import com.google.common.primitives.Ints;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;


public class Sync extends JavaPlugin
{
	static String uri, username, password;
	HashMap<String, String> groupNameConversions = new HashMap<String, String>();
	ArrayList<String> playerExceptions = new ArrayList<String>();
    Permission permission = null;
    static Sync instance;
    private boolean useMemberFeature, requireValidEmail, nameSystem, useProfileFields, subGroups;
    private String defaultGroupName, profileValue, databaseTable, xenId, playerId;
	private static String memCommand;
    
    public static Sync getInstance() {
    	return instance;
    }
    
    @Override
    public void onEnable() {
    	this.saveDefaultConfiguration();
    	this.loadConfig();
    	this.setupDatabase();
    	this.setupConversionFile();
        this.setupExceptionFile();
        this.setupPermissions();
        
        this.getServer().getPluginManager().registerEvents((Listener)new Listener() {
        	private void syncPlayer(final Player player) {
        		if(!Sync.this.playerExceptions.contains(player.getName())) {
        			final String[] groups = Sync.this.permission.getPlayerGroups(player);
        			final String primaryGroup = Sync.this.permission.getPrimaryGroup(player);
        			final String forumPrimaryGroup = Sync.this.groupNameConversions.get(primaryGroup);
        			
        			for(int i = 0; i < groups.length; i++) {
        				final String xenGroup = Sync.this.groupNameConversions.get(groups[i]);
        				if (xenGroup != null || xenGroup != "") {
                            groups[i] = xenGroup;
                        }
        			}
        			
					final String[] forumSecondaryGroups = groups;
					if(forumPrimaryGroup != null) {
						Bukkit.getScheduler().runTaskAsynchronously((Plugin)Sync.instance, (Runnable)new Runnable() {
							@Override
							public void run() {
								if(!Sync.this.subGroups) {
									//Sync.this.synchronisePlayer(Sync.this.getUserID(Sync.this.getPlayerIdentifier(player)), Sync.this.getGroupID(forumPrimaryGroup));
									Sync.this.synchronisePlayer(Sync.this.getUserID(Sync.this.getPlayerIdentifier(player)), 2);
								} else {
									final int[] forumSecondaryGroupIDs = new int[forumSecondaryGroups.length];
									for(int i = 0; i < forumSecondaryGroups.length; i++) {
										final int gID = Sync.this.getGroupID(forumSecondaryGroups[i]);
										if (gID != -1) {
                                            forumSecondaryGroupIDs[i] = gID;
                                        }
									}
									Sync.this.synchronisePlayer(Sync.this.getUserID(Sync.this.getPlayerIdentifier(player)), 2, forumSecondaryGroupIDs);
								}
							}
						});
					}
        		}
        	}
        	
        	@EventHandler
        	public void onPlayerJoin(final PlayerJoinEvent event) {
        		this.syncPlayer(event.getPlayer());
        	}
        }, (Plugin)this);
        
        Sync.instance = this;
        
    	super.onEnable();
    }
    
    private void loadConfig() {
    	uri = this.getConfig().getString("xenforo-mysql-uri");
        username = this.getConfig().getString("xenforo-mysql-user");
        password = this.getConfig().getString("xenforo-mysql-pass");
        this.subGroups = this.getConfig().getBoolean("sub-groups");
        this.profileValue = this.getConfig().getString("player-identifier.field");
        this.nameSystem = this.getConfig().getBoolean("player-identifier.name-system");
        this.useProfileFields = this.getConfig().getBoolean("player-identifier.use-profile-fields");
        this.databaseTable = this.getConfig().getString("player-identifier.database-table");
        this.xenId = this.getConfig().getString("player-identifier.table-xenid");
        this.playerId = this.getConfig().getString("player-identifier.table-playerid");
        this.useMemberFeature = this.getConfig().getBoolean("member-feature.enable");
        this.requireValidEmail = this.getConfig().getBoolean("member-feature.require-valid-email");
        memCommand = this.getConfig().getString("member-feature.commands-to-run");
        this.defaultGroupName = this.getConfig().getString("member-feature.default-group-name");
    }
    
    private void saveDefaultConfiguration() {
    	getConfig().addDefault("xenforo-mysql-uri", "localhost/database");
    	getConfig().addDefault("xenforo-mysql-user", "user");
    	getConfig().addDefault("xenforo-mysql-pass", "pass");
    	getConfig().addDefault("sub-groups", false);
    	getConfig().addDefault("player-identifier.field", "minecraft_username");
    	getConfig().addDefault("player-identifier.name-system", true);
    	getConfig().addDefault("player-identifier.use-profile-fields", true);
    	getConfig().addDefault("player-identifier.database-table", "xf_user_field_value");
    	getConfig().addDefault("player-identifier.table-xenid", "user_id");
    	getConfig().addDefault("player-identifier.table-playerid", "minecraft_usernmae");
    	getConfig().addDefault("member-feature.enable", true);
    	getConfig().addDefault("member-feature.require-valid-email", true);
    	getConfig().addDefault("member-feature.commands-to-run", "/pex user %player% group set Member");
    	getConfig().addDefault("member-feature.default-group-name", "default");
    	getConfig().options().copyDefaults(true);
    	saveConfig();
    }
    
    @SuppressWarnings("unchecked")
	private boolean setupPermissions() {
        RegisteredServiceProvider<?> permissionProvider = this.getServer().getServicesManager().getRegistration((Class)Permission.class);
        if (permissionProvider != null) {
            this.permission = (Permission)permissionProvider.getProvider();
        }
        if (this.permission != null) {
            return true;
        }
        return false;
    }
    
    private void setupConversionFile() {
        try {
            String line;
            File cFile = new File(this.getDataFolder(), "groupconversions");
            if (!cFile.exists()) {
                cFile.createNewFile();
            }
            BufferedReader br = new BufferedReader(new FileReader(cFile));
            while ((line = br.readLine()) != null) {
                this.groupNameConversions.put(line.split(":")[0], line.split(":")[1]);
            }
            br.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setupExceptionFile() {
        try {
            String line;
            File eFile = new File(this.getDataFolder(), "playerexceptions");
            if (!eFile.exists()) {
                eFile.createNewFile();
            }
            BufferedReader br = new BufferedReader(new FileReader(eFile));
            while ((line = br.readLine()) != null) {
                this.playerExceptions.add(line);
            }
            br.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + uri, username, password);
    }

    private void setupDatabase() {
        try {
            if (!this.isDriverLoaded()) {
                Class.forName("com.mysql.jdbc.Driver").newInstance();
            }
        }
        catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
            e.printStackTrace();
        }
    }

    protected void synchronisePlayer(int userid, int groupid, int[] forumSecondaryGroupIDs) {
        this.synchronisePlayer(userid, groupid);
        List<Integer> list = Ints.asList((int[])forumSecondaryGroupIDs);
        String cdl = Joiner.on((String)",").join((Iterable<?>)list);
        try {
            Connection con = Sync.getConnection();
            con.createStatement().executeUpdate("UPDATE `xf_user` SET `secondary_group_ids` = '" + cdl + "' WHERE `user_id` = '" + userid + "'");
            con.close();
        }
        catch (SQLException e) {
            e.printStackTrace();
        }
    }

    protected void synchronisePlayer(int userid, int groupid) {
        try {
            Connection con = Sync.getConnection();
            con.createStatement().executeUpdate("UPDATE `xf_user` SET `user_group_id` = '" + groupid + "' WHERE `user_id` = '" + userid + "'");
            con.createStatement().executeUpdate("UPDATE `xf_user` SET `display_style_group_id` = '" + groupid + "' WHERE `user_id` = '" + userid + "'");
            con.close();
        }
        catch (SQLException e) {
            e.printStackTrace();
        }
    }

    protected int getUserID(String identifier) {
        if (this.useProfileFields) {
            return this.getUserIDNormal(identifier);
        }
        return this.getUserIDCustom(identifier);
    }

    protected int getUserIDNormal(String identifier) {
        try {
            int id;
            Connection con = Sync.getConnection();
            ResultSet rs = con.createStatement().executeQuery("SELECT `user_id` FROM `xf_user_field_value` WHERE (`field_id` = '" + this.profileValue + "' AND `field_value` = '" + identifier + "')");
            if (rs.first()) {
                if (!rs.next()) {
                    rs.first();
                    id = rs.getInt("user_id");
                } else {
                    this.warn("Two or more forum users with the minecraft name of: " + identifier);
                    id = -1;
                }
            } else {
                this.warn("No forum users with minecraft name of: " + identifier);
                id = -1;
            }
            rs.close();
            con.close();
            return id;
        }
        catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    protected int getUserIDCustom(String identifier) {
        try {
            int id;
            Connection con = Sync.getConnection();
            ResultSet rs = con.createStatement().executeQuery("SELECT `" + this.xenId + "` FROM `" + this.databaseTable + "` WHERE `" + this.playerId + "` = '" + identifier + "'");
            if (rs.first()) {
                if (!rs.next()) {
                    rs.first();
                    id = rs.getInt(this.xenId);
                } else {
                    this.warn("Two or more forum users with an identifier of: " + identifier);
                    id = -1;
                }
            } else {
                this.warn("No forum users with the identifier of: " + identifier);
                id = -1;
            }
            rs.close();
            con.close();
            return id;
        }
        catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    protected int getGroupID(String groupName) {
        block4 : {
            try {
                Connection con = Sync.getConnection();
                ResultSet rs = con.createStatement().executeQuery("SELECT `user_group_id` FROM `xf_user_group` WHERE `title` = '" + groupName + "'");
                if (!rs.first()) break block4;
                if (!rs.next()) {
                    rs.first();
                    return rs.getInt("user_group_id");
                }
                this.warn("Two or more forum groups with the name of: " + groupName);
                return -1;
            }
            catch (SQLException e) {
                e.printStackTrace();
                return -1;
            }
        }
        this.warn("No forum groups with name of: " + groupName);
        return -1;
    }

    private boolean isDriverLoaded() {
        boolean loaded = false;
        Enumeration<Driver> e = DriverManager.getDrivers();
        while (e.hasMoreElements()) {
            String name = e.nextElement().getClass().getName();
            if (!name.equalsIgnoreCase("com.mysql.jdbc.Driver")) continue;
            loaded = true;
        }
        return loaded;
    }

    private void warn(final String str) {
        Bukkit.getScheduler().runTask((Plugin)this, new Runnable(){

            @Override
            public void run() {
                Bukkit.getLogger().warning("[Wizard Sync] " + str);
            }
        });
    }

    private String getPlayerIdentifier(Player player) {
        return this.nameSystem ? player.getName() : player.getUniqueId().toString();
    }

    public boolean isEligableForMember(Player player) {
        return this.isEligableForMember(this.getUserID(this.getPlayerIdentifier(player)));
    }

    private boolean isEligableForMember(int userid) {
        try {
            Connection con = Sync.getConnection();
            ResultSet rs = con.createStatement().executeQuery("SELECT `user_state` FROM `xf_user` WHERE `user_id` = '" + userid + "'");
            if (rs.first() && !rs.next()) {
                rs.first();
                boolean valid = rs.getString("user_state").equalsIgnoreCase("valid");
                rs.close();
                con.close();
                return this.requireValidEmail ? valid : true;
            }
            rs.close();
            con.close();
        }
        catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static void memberify(Player player) {
        Bukkit.getServer().dispatchCommand((CommandSender)Bukkit.getConsoleSender(), memCommand.replace("/", "").replace("%player%", player.getName()));
    }
    
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
    	if(!this.useMemberFeature) {
    		sender.sendMessage("[Wizard Sync] The member system is not enabled.");
    		return true;
    	}
    	
    	if(!(sender instanceof Player)) {
    		sender.sendMessage("[Wizard Sync] You must be a player to perform this command.");
    		return true;
    	}
    	
    	final Player player;
    	
    	if(args.length >= 1) {
    		Player tempPlayer;
    		
    		try {
                tempPlayer = Bukkit.getPlayer(UUID.fromString(args[0]));
            }
            catch (IllegalArgumentException e) {
                tempPlayer = Bukkit.getPlayer(args[0]);
            }
            player = tempPlayer;
    	} else {
    		player = (Player)sender;
    	}
    	
    	if(player == null) {
    		sender.sendMessage("[Wizard Sync] Player is null. What the heck?");
    		return true;
    	}
    	
    	if(this.permission.getPrimaryGroup(player).equalsIgnoreCase(this.defaultGroupName)) {
    		Bukkit.getScheduler().runTaskAsynchronously((Plugin)this, (Runnable)new Runnable() {
    			@Override
    			public void run() {
    				if (Sync.this.isEligableForMember(Sync.this.getUserID(Sync.this.getPlayerIdentifier(player)))) {
                        Bukkit.getScheduler().runTask((Plugin)Sync.instance, (Runnable)new Runnable() {
                            @Override
                            public void run() {
                                Sync.memberify(player);
                                player.sendMessage("[Wizard Sync] Account Linked.");
                            }
                        });
                    } else {
                    	player.sendMessage("[Wizard Sync] You are not eligable to become a member yet!");
                    	player.sendMessage("[Wizard Sync] You must create an account on our website,");
                    	player.sendMessage("[Wizard Sync] Enter your minecraft name into the designated field");
                    	player.sendMessage("[Wizard Sync] And verify your email!");
                    	player.sendMessage("[Wizard Sync] If you are still having problems, contact one of our staff.");
                    }
    			}
    		});
    		
    		return true;
    	} else {
    		player.sendMessage("[Wizard Sync] You're already synced up.");
    		return true;
    	}
    }
}








