package tr.escmenu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ServerLinks;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLinksSendEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EscMenuPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private volatile byte[] packHash;
    private volatile boolean packReady;
    private UUID packId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        preparePack();
    }

    // ------------------------------------------------------------------
    //  Resource pack hazirlama
    // ------------------------------------------------------------------
    private void preparePack() {
        packReady = false;
        FileConfiguration cfg = getConfig();
        if (!cfg.getBoolean("resource-pack.enabled", true)) {
            getLogger().info("Resource pack gonderimi kapali.");
            return;
        }

        final String url = cfg.getString("resource-pack.url", "");
        if (url == null || url.isBlank() || url.contains("ORNEK")) {
            getLogger().warning("config.yml icinde resource-pack.url ayarlanmamis!");
            return;
        }
        final int version = cfg.getInt("resource-pack.version", 1);
        packId = UUID.nameUUIDFromBytes((url + "#" + version).getBytes());

        final String configured = cfg.getString("resource-pack.sha1", "").trim();
        if (configured.length() == 40) {
            packHash = hexToBytes(configured);
            packReady = true;
            getLogger().info("Resource pack hazir (config'teki hash kullanildi).");
            return;
        }

        // Hash bos -> linkten indirip hesapla (async)
        getLogger().info("Resource pack hash'i hesaplaniyor...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.ALWAYS).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) {
                    getLogger().warning("Pack indirilemedi, HTTP " + res.statusCode());
                    return;
                }
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                try (InputStream in = res.body()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) sha1.update(buf, 0, n);
                }
                packHash = sha1.digest();
                packReady = true;
                getLogger().info("Resource pack hazir.");
            } catch (Exception e) {
                getLogger().warning("Pack hash'i hesaplanamadi: " + e.getMessage());
            }
        });
    }

    private void sendPack(Player player) {
        if (!packReady) return;
        FileConfiguration cfg = getConfig();
        Component prompt = MM.deserialize(cfg.getString("resource-pack.prompt", ""));
        player.setResourcePack(packId, cfg.getString("resource-pack.url"), packHash, prompt,
                cfg.getBoolean("resource-pack.force", false));
    }

    // ------------------------------------------------------------------
    //  Olaylar
    // ------------------------------------------------------------------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        sendPack(e.getPlayer());
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent e) {
        if (!getConfig().getBoolean("resource-pack.force", false)) return;
        switch (e.getStatus()) {
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD ->
                    e.getPlayer().kick(MM.deserialize(getConfig().getString("resource-pack.kick-message", "")));
            default -> { }
        }
    }

    /** ESC menusundeki "Sunucu Baglantilari" (1.21+). */
    @EventHandler
    public void onLinks(PlayerLinksSendEvent e) {
        ServerLinks links = e.getLinks();
        List<Map<?, ?>> list = getConfig().getMapList("server-links");
        for (Map<?, ?> m : list) {
            Object urlObj = m.get("url");
            if (urlObj == null) continue;
            try {
                URI uri = URI.create(String.valueOf(urlObj));
                Object type = m.get("type");
                Object name = m.get("name");
                if (type != null) {
                    links.addLink(ServerLinks.Type.valueOf(String.valueOf(type).toUpperCase()), uri);
                } else if (name != null) {
                    links.addLink(String.valueOf(name), uri);
                }
            } catch (Exception ex) {
                getLogger().warning("Gecersiz server-links kaydi: " + m + " (" + ex.getMessage() + ")");
            }
        }
    }

    // ------------------------------------------------------------------
    //  Komutlar
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        FileConfiguration cfg = getConfig();

        if (cmd.getName().equalsIgnoreCase("discord")) {
            String url = cfg.getString("discord.url", "");
            sender.sendMessage(MM.deserialize(cfg.getString("discord.message", "{url}").replace("{url}", url)));
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("escmenu")) {
            if (!sender.hasPermission("escmenu.admin")) {
                sender.sendMessage(MM.deserialize(cfg.getString("messages.no-permission", "")));
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage(MM.deserialize(cfg.getString("messages.usage", "")));
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "reload" -> {
                    reloadConfig();
                    preparePack();
                    sender.sendMessage(MM.deserialize(getConfig().getString("messages.reloaded", "")));
                }
                case "pack" -> {
                    if (!packReady) {
                        sender.sendMessage(MM.deserialize(cfg.getString("messages.pack-not-ready", "")));
                    } else {
                        Bukkit.getOnlinePlayers().forEach(this::sendPack);
                        sender.sendMessage(MM.deserialize(cfg.getString("messages.pack-resent", "")));
                    }
                }
                default -> sender.sendMessage(MM.deserialize(cfg.getString("messages.usage", "")));
            }
            return true;
        }
        return false;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
