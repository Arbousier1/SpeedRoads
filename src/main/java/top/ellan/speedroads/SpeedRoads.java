package top.ellan.speedroads;  
  
import net.kyori.adventure.text.Component;  
import net.kyori.adventure.text.format.NamedTextColor;  
import org.bukkit.Bukkit;  
import org.bukkit.Location;  
import org.bukkit.Material;  
import org.bukkit.NamespacedKey;  
import org.bukkit.attribute.Attribute;  
import org.bukkit.attribute.AttributeInstance;  
import org.bukkit.attribute.AttributeModifier;  
import org.bukkit.configuration.ConfigurationSection;  
import org.bukkit.entity.LivingEntity;  
import org.bukkit.entity.Player;  
import org.bukkit.event.EventHandler;  
import org.bukkit.event.Listener;  
import org.bukkit.event.player.PlayerQuitEvent;  
import org.bukkit.plugin.java.JavaPlugin;  
import org.bukkit.scheduler.BukkitTask;  
  
import java.lang.reflect.Method;  
import java.util.HashMap;  
import java.util.Map;  
import java.util.UUID;  
  
public class SpeedRoads extends JavaPlugin implements Listener {  
  
    private final NamespacedKey ROAD_SPEED_KEY = new NamespacedKey(this, "road_speed_modifier");  
    private final Map<Material, Double> speedMap = new HashMap<>();  
    private final Map<UUID, PlayerCache> playerStates = new HashMap<>();  
  
    private BukkitTask task;  
    private int checkInterval;  
    private int maxCheckHeight;  
    private long maxProcessingTimeNanos;  
    private boolean skipUnloaded;  
      
    // Reflection methods for NMS access  
    private Method getHandleMethod;  
    private Method getChunkIfLoadedMethod;  
    private Method getBlockStateIfLoadedMethod;  
    private Method minecraftToBukkitMethod;  
  
    @Override  
    public void onEnable() {  
        saveDefaultConfig();  
        loadSettings();  
        setupReflection();  
        getServer().getPluginManager().registerEvents(this, this);  
        startTask();  
        getComponentLogger().info(Component.text("SpeedRoads (Paper NMS 深度优化版) 已启动").color(NamedTextColor.AQUA));  
    }  
  
    private void setupReflection() {  
        try {  
            // CraftWorld.getHandle()  
            getHandleMethod = Class.forName("org.bukkit.craftbukkit.CraftWorld")  
                .getMethod("getHandle");  
              
            // ServerLevel.getChunkIfLoadedImmediately(int, int)  
            getChunkIfLoadedMethod = Class.forName("net.minecraft.server.level.ServerLevel")  
                .getMethod("getChunkIfLoadedImmediately", int.class, int.class);  
              
            // ServerLevel.getBlockStateIfLoaded(BlockPos)  
            getBlockStateIfLoadedMethod = Class.forName("net.minecraft.server.level.ServerLevel")  
                .getMethod("getBlockStateIfLoaded", Class.forName("net.minecraft.core.BlockPos"));  
              
            // CraftBlockType.minecraftToBukkit(Block)  
            minecraftToBukkitMethod = Class.forName("org.bukkit.craftbukkit.block.CraftBlockType")  
                .getMethod("minecraftToBukkit", Class.forName("net.minecraft.world.level.block.Block"));  
        } catch (Exception e) {  
            getLogger().warning("Failed to setup NMS reflection: " + e.getMessage());  
        }  
    }  
  
    private void loadSettings() {  
        reloadConfig();  
        speedMap.clear();  
        playerStates.clear();  
  
        checkInterval = getConfig().getInt("settings.check-interval", 5);  
        maxCheckHeight = getConfig().getInt("settings.max-check-height", 3);  
        maxProcessingTimeNanos = getConfig().getLong("settings.paper.max-processing-time-nanos", 25000000L);  
        skipUnloaded = getConfig().getBoolean("settings.paper.skip-unloaded-chunks", true);  
  
        ConfigurationSection pathSection = getConfig().getConfigurationSection("paths");  
        if (pathSection != null) {  
            for (String key : pathSection.getKeys(false)) {  
                Material m = Material.matchMaterial(key);  
                if (m != null) speedMap.put(m, pathSection.getDouble(key) * 0.2);  
            }  
        }  
    }  
  
    private void processPlayers() {  
        if (Bukkit.getOnlinePlayers().isEmpty() || Bukkit.isStopping()) return;  
  
        long startTime = System.nanoTime();  
  
        for (Player player : Bukkit.getOnlinePlayers()) {  
            if (System.nanoTime() - startTime > maxProcessingTimeNanos) break;  
  
            if (!isValid(player)) {  
                cleanup(player);  
                continue;  
            }  
  
            LivingEntity target = (player.isInsideVehicle() && player.getVehicle() instanceof LivingEntity vehicle) ? vehicle : player;  
            UUID worldUID = target.getWorld().getUID();  
              
            // 获取 NMS Handle via reflection  
            Object nmsLevel = null;  
            try {  
                nmsLevel = getHandleMethod.invoke(target.getWorld());  
            } catch (Exception e) {  
                continue; // Fallback if reflection fails  
            }  
  
            double targetSpeed = 0.0;  
            int x = target.getLocation().getBlockX();  
            int y = (int) Math.floor(target.getLocation().getY() + 0.1);  
            int z = target.getLocation().getBlockZ();  
  
            PlayerCache cache = playerStates.computeIfAbsent(player.getUniqueId(), k -> new PlayerCache());  
  
            if (cache.isValid(worldUID, x, y, z)) {  
                targetSpeed = cache.speed;  
            } else {  
                // Paper 优化: 检查区块是否已加载  
                try {  
                    Object chunk = getChunkIfLoadedMethod.invoke(nmsLevel, x >> 4, z >> 4);  
                    if (chunk == null && skipUnloaded) {  
                        continue;  
                    }  
                } catch (Exception e) {  
                    // Fallback to standard API if reflection fails  
                    if (!target.getWorld().isChunkLoaded(x >> 4, z >> 4) && skipUnloaded) {  
                        continue;  
                    }  
                }  
  
                for (int i = 0; i <= maxCheckHeight; i++) {  
                    int checkY = y - i;  
                    if (checkY < target.getWorld().getMinHeight()) break;  
  
                    // Paper 优化: 直接获取 NMS BlockState  
                    try {  
                        Object blockPos = Class.forName("net.minecraft.core.BlockPos")  
                            .getConstructor(int.class, int.class, int.class)  
                            .newInstance(x, checkY, z);  
                        Object nmsState = getBlockStateIfLoadedMethod.invoke(nmsLevel, blockPos);  
                          
                        if (nmsState != null) {  
                            Object nmsBlock = nmsState.getClass().getMethod("getBlock").invoke(nmsState);  
                            Material type = (Material) minecraftToBukkitMethod.invoke(null, nmsBlock);  
                              
                            if (!type.isAir() && type != Material.WATER && type != Material.LAVA) {  
                                targetSpeed = speedMap.getOrDefault(type, 0.0);  
                                if (targetSpeed > 0) break;  
                            }  
                        }  
                    } catch (Exception e) {  
                        // Fallback to standard API if reflection fails  
                        Location loc = new Location(target.getWorld(), x, checkY, z);  
                        Material type = loc.getBlock().getType();  
                        if (!type.isAir() && type != Material.WATER && type != Material.LAVA) {  
                            targetSpeed = speedMap.getOrDefault(type, 0.0);  
                            if (targetSpeed > 0) break;  
                        }  
                    }  
                }  
                cache.update(worldUID, x, y, z, targetSpeed);  
            }  
  
            updateAttributes(player, target, targetSpeed, cache);  
        }  
    }  
  
    private void updateAttributes(Player player, LivingEntity target, double targetSpeed, PlayerCache cache) {  
        double prev = cache.lastAppliedSpeed;  
        if (Math.abs(targetSpeed - prev) > 0.0001) {  
            applyAttributeSpeed(target, targetSpeed);  
              
            if (targetSpeed > 0 && prev == 0) {  
                player.sendActionBar(Component.text(target instanceof Player ? "⚡ 路面加速中" : "🐎 坐骑加速中", NamedTextColor.GREEN));  
            } else if (targetSpeed == 0 && prev > 0) {  
                player.sendActionBar(Component.text("💨 加速结束", NamedTextColor.YELLOW));  
            }  
            cache.lastAppliedSpeed = targetSpeed;  
        }  
    }  
  
    private void applyAttributeSpeed(LivingEntity entity, double amount) {  
        AttributeInstance ai = entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);  
        if (ai == null) return;  
  
        AttributeModifier existing = ai.getModifier(ROAD_SPEED_KEY);  
        if (existing != null) {  
            if (Math.abs(existing.getAmount() - amount) < 0.0001) return;  
            ai.removeModifier(existing);  
        }  
  
        if (amount > 0) {  
            ai.addModifier(new AttributeModifier(ROAD_SPEED_KEY, amount, AttributeModifier.Operation.ADD_SCALAR));  
        }  
    }  
  
    private boolean isValid(Player p) {  
        return p.isValid() && !p.isFlying() && !p.isGliding() && !p.isSwimming();  
    }  
  
    private void cleanup(Player p) {  
        if (playerStates.remove(p.getUniqueId()) != null) {  
            clearSpeed(p);  
            if (p.isInsideVehicle() && p.getVehicle() instanceof LivingEntity v) clearSpeed(v);  
        }  
    }  
  
    private void clearSpeed(LivingEntity entity) {  
        AttributeInstance ai = entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);  
        if (ai != null) ai.removeModifier(ROAD_SPEED_KEY);  
    }  
  
    private static class PlayerCache {  
        UUID worldUID;  
        long posKey;  
        int y;  
        double speed;  
        double lastAppliedSpeed;  
        boolean initialized = false;  
  
        boolean isValid(UUID w, int nx, int ny, int nz) {  
            return initialized && w.equals(worldUID) && y == ny && posKey == ((long) nx << 32 | (nz & 0xFFFFFFFFL));  
        }  
  
        void update(UUID w, int nx, int ny, int nz, double s) {  
            this.worldUID = w;  
            this.posKey = ((long) nx << 32 | (nz & 0xFFFFFFFFL));  
            this.y = ny;  
            this.speed = s;  
            this.initialized = true;  
        }  
    }  
  
    private void startTask() {  
        if (task != null) task.cancel();  
        task = Bukkit.getScheduler().runTaskTimer(this, this::processPlayers, 20L, checkInterval);  
    }  
  
    @EventHandler public void onQuit(PlayerQuitEvent e) { cleanup(e.getPlayer()); }  
}