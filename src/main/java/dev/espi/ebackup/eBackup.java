package dev.espi.ebackup;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/*
   Copyright 2020 EspiDev

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

public class eBackup extends JavaPlugin implements CommandExecutor, Listener, TabExecutor {

    // lock
    AtomicBoolean isInBackup = new AtomicBoolean(false);
    AtomicBoolean isInUpload = new AtomicBoolean(false);

    // config options
    String crontask, backupFormat, backupDateFormat;
    File backupPath;
    int maxBackups;
    boolean onlyBackupIfPlayersWereOn;
    boolean deleteAfterUpload;
    int compressionLevel;

    String ftpType, ftpHost, ftpUser, ftpPass, ftpPath, sftpPrivateKeyPath, sftpPrivateKeyPassword;
    int ftpPort;
    boolean ftpEnable, useSftpKeyAuth;

    boolean backupPluginJars, backupPluginConfs;
    List<String> filesToIgnore;
    List<File> ignoredFiles = new ArrayList<>();

    BukkitTask bukkitCronTask = null;

    // track if players were on
    AtomicBoolean playersWereOnSinceLastBackup = new AtomicBoolean(false);
    
    // messages configuration
    FileConfiguration messages;
    File messagesFile;

    // called on reload and when the plugin first loads
    public void loadPlugin() {
        ignoredFiles.clear();

        saveDefaultConfig();
        getConfig().options().copyDefaults(true);

        if (!getDataFolder().exists())
            getDataFolder().mkdir();

        try {
            getConfig().load(getDataFolder() + "/config.yml");
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
        }

        // register events
        getServer().getPluginManager().registerEvents(this, this);

        // load messages
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        
        // load config data
        crontask = getConfig().getString("crontask");
        backupFormat = getConfig().getString("backup-format");
        backupDateFormat = getConfig().getString("backup-date-format");
        backupPath = new File(getConfig().getString("backup-path"));
        maxBackups = getConfig().getInt("max-backups");
        onlyBackupIfPlayersWereOn = getConfig().getBoolean("only-backup-if-players-were-on");
        deleteAfterUpload = getConfig().getBoolean("delete-after-upload");
        compressionLevel = getConfig().getInt("compression-level");
        if (!getConfig().contains("compression-level") || compressionLevel > 9 || compressionLevel < 0) {
            if (compressionLevel > 9 || compressionLevel < 0) {
                getLogger().warning(messages.getString("logs.compression.invalid_level"));
            }
            compressionLevel = 4;
        }

        ftpEnable = getConfig().getBoolean("ftp.enable");
        ftpType = getConfig().getString("ftp.type");
        ftpHost = getConfig().getString("ftp.host");
        ftpPort = getConfig().getInt("ftp.port");
        ftpUser = getConfig().getString("ftp.user");
        ftpPass = getConfig().getString("ftp.pass");
        useSftpKeyAuth = getConfig().getBoolean("ftp.use-key-auth");
        sftpPrivateKeyPath = getConfig().getString("ftp.private-key");
        sftpPrivateKeyPassword = getConfig().getString("ftp.private-key-password");
        ftpPath = getConfig().getString("ftp.path");
        backupPluginJars = getConfig().getBoolean("backup.pluginjars");
        backupPluginConfs = getConfig().getBoolean("backup.pluginconfs");
        filesToIgnore = getConfig().getStringList("backup.ignore");
        for (String s : filesToIgnore) {
            ignoredFiles.add(new File(s));
        }

        // make sure backup location exists
        if (!backupPath.exists())
            backupPath.mkdir();

        // stop cron task if it is running
        if (bukkitCronTask != null)
            bukkitCronTask.cancel();

        // start cron task
        CronUtil.checkCron();
        bukkitCronTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (CronUtil.run()) {
                if (isInBackup.get()) {
                    getLogger().warning(messages.getString("logs.backup.scheduled_skipped"));
                } else if (onlyBackupIfPlayersWereOn && !playersWereOnSinceLastBackup.get()) {
                    getLogger().info(messages.getString("logs.backup.no_players_skipped"));
                } else {
                    BackupUtil.doBackup(true);

                    if (Bukkit.getServer().getOnlinePlayers().size() == 0) {
                        playersWereOnSinceLastBackup.set(false);
                    }
                }
            }
        }, 20, 20);
    }

    @Override
    public void onEnable() {
        getLogger().info("Initializing eBackup...");

        try {
            Metrics metrics = new Metrics(this);
        } catch (NoClassDefFoundError ignored) {
            // ignore if metrics is broken for old versions
        }
        this.getCommand("ebackup").setExecutor(this);

        loadPlugin();

        getLogger().info(messages.getString("logs.initialized"));
    }

    @Override
    public void onDisable() {
        if (isInBackup.get() || isInUpload.get()) {
            if (messages != null) {
                getLogger().info(messages.getString("logs.disabling.tasks_cancelled"));
            } else {
                getLogger().info("Any running tasks (uploads or backups) will now be cancelled due to the server shutdown.");
            }
        }
        Bukkit.getScheduler().cancelTasks(this);

        if (messages != null) {
            getLogger().info(messages.getString("logs.disabled"));
        } else {
            getLogger().info("Disabled eBackup!");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent e) {
        playersWereOnSinceLastBackup.set(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + messages.getString("commands.help"));
            return true;
        }

        switch (args[0]) {
            case "help":
                sender.sendMessage(ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "=====" + ChatColor.RESET + ChatColor.DARK_AQUA + messages.getString("help.header").replace("{version}", getPlugin().getDescription().getVersion()) + ChatColor.RESET + ChatColor.GRAY + ChatColor.STRIKETHROUGH + "=====");
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + messages.getString("help.backup"));
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + messages.getString("help.backuplocal"));
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + messages.getString("help.list"));
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + messages.getString("help.stats"));
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + messages.getString("help.testupload"));
                sender.sendMessage(ChatColor.AQUA + "> " + ChatColor.GRAY + messages.getString("help.reload"));
                break;
            case "backup":
                if (isInBackup.get()) {
                    sender.sendMessage(ChatColor.RED + messages.getString("commands.backup.in_progress"));
                } else {
                    sender.sendMessage(ChatColor.GRAY + messages.getString("commands.backup.starting"));
                    Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), () -> {
                        BackupUtil.doBackup(true);
                        if (sender instanceof Player) {
                            sender.sendMessage(ChatColor.GRAY + messages.getString("commands.backup.finished"));
                        }
                    });
                }
                break;
            case "backuplocal":
                if (isInBackup.get()) {
                    sender.sendMessage(ChatColor.RED + messages.getString("commands.backuplocal.in_progress"));
                } else {
                    sender.sendMessage(ChatColor.GRAY + messages.getString("commands.backuplocal.starting"));
                    Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), () -> {
                        BackupUtil.doBackup(false);
                        sender.sendMessage(ChatColor.GRAY + messages.getString("commands.backuplocal.finished"));
                    });
                }
                break;
            case "list":
                sender.sendMessage(ChatColor.AQUA + messages.getString("commands.list"));
                for (File f : getPlugin().backupPath.listFiles()) {
                    sender.sendMessage(ChatColor.GRAY + "- " + f.getName());
                }
                break;
            case "stats":
                sender.sendMessage(ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "=====" + ChatColor.RESET + ChatColor.DARK_AQUA + messages.getString("stats.header") + ChatColor.RESET + ChatColor.GRAY + ChatColor.STRIKETHROUGH + "=====");
                sender.sendMessage(ChatColor.AQUA + messages.getString("stats.total_size").replace("{size}", String.valueOf(getPlugin().backupPath.getTotalSpace()/1024/1024/1024)));
                sender.sendMessage(ChatColor.AQUA + messages.getString("stats.usable_space").replace("{size}", String.valueOf(getPlugin().backupPath.getUsableSpace()/1024/1024/1024)));
                sender.sendMessage(ChatColor.AQUA + messages.getString("stats.free_space").replace("{size}", String.valueOf(getPlugin().backupPath.getFreeSpace()/1024/1024/1024)));
                break;
            case "testupload":
                sender.sendMessage(ChatColor.GRAY + messages.getString("commands.testupload.starting"));
                if (sender instanceof Player) {
                    sender.sendMessage(ChatColor.AQUA + messages.getString("commands.testupload.check_console"));
                }
                BackupUtil.testUpload();
                break;
            case "reload":
                sender.sendMessage(ChatColor.GRAY + messages.getString("commands.reload.starting"));
                loadPlugin();
                sender.sendMessage(ChatColor.GRAY + messages.getString("commands.reload.finished"));
                break;
            default:
                sender.sendMessage(ChatColor.AQUA + messages.getString("commands.help"));
                break;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> commandTab = new ArrayList<>();
        commandTab.add("help");
        commandTab.add("backup");
        commandTab.add("backuplocal");
        commandTab.add("list");
        commandTab.add("stats");
        commandTab.add("testupload");
        commandTab.add("reload");
        if (args.length == 1) {
            return commandTab;
        }
        return null;
    }

    public static eBackup getPlugin() {
        return (eBackup) Bukkit.getPluginManager().getPlugin("eBackup");
    }

}
