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

import java.lang.reflect.Constructor;
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

    // 反射缓存
    private Method getHandleMethod;
    private Method getChunkIfLoadedMethod;
    private Method getBlockStateIfLoadedMethod;
    private Method minecraftToBukkitMethod;
    private Constructor<?> blockPosConstructor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        setupReflection();
        getServer().getPluginManager().registerEvents(this, this);
        startTask();
        getComponentLogger().info(Component.text("SpeedRoads (Paper 兼容反射版) 已启动").color(NamedTextColor.AQUA));
    }

    private void setupReflection() {
        try {
            // 获取 CraftWorld -> ServerLevel
            getHandleMethod = Class.forName("org.bukkit.craftbukkit.CraftWorld").getMethod("getHandle");
            
            Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            
            // 获取 Paper 优化方法
            getChunkIfLoadedMethod = serverLevelClass.getMethod("getChunkIfLoadedImmediately", int.class, int.class);
            getBlockStateIfLoadedMethod = serverLevelClass.getMethod("getBlockStateIfLoaded", blockPosClass);
            
            // BlockPos 构造函数
            blockPosConstructor = blockPosClass.getConstructor(int.class, int.class, int.class);
            
            // NMS Block -> Bukkit Material 转换
            minecraftToBukkitMethod = Class.forName("org.bukkit.craftbukkit.block.CraftBlockType")
                    .getMethod("minecraftToBukkit", Class.forName("net.minecraft.world.level.block.Block"));
            
        } catch (Exception e) {
            getLogger().warning("无法初始化 Paper NMS 反射，将回退至标准 API: " + e.getMessage());
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
            
            double targetSpeed = 0.0;
            int x = target.getLocation().getBlockX();
            int y = (int) Math.floor(target.getLocation().getY() + 0.1);
            int z = target.getLocation().getBlockZ();

            PlayerCache cache = playerStates.computeIfAbsent(player.getUniqueId(), k -> new PlayerCache());

            if (cache.isValid(worldUID, x, y, z)) {
                targetSpeed = cache.speed;
            } else {
                targetSpeed = getRoadSpeedWithOptimization(target, x, y, z);
                cache.update(worldUID, x, y, z, targetSpeed);
            }

            updateAttributes(player, target, targetSpeed, cache);
        }
    }

    private double getRoadSpeedWithOptimization(LivingEntity target, int x, int y, int z) {
        // 反射尝试使用 Paper 优化路径
        if (getHandleMethod != null) {
            try {
                Object nmsLevel = getHandleMethod.invoke(target.getWorld());
                
                // 1. 检查区块加载
                if (getChunkIfLoadedMethod.invoke(nmsLevel, x >> 4, z >> 4) == null && skipUnloaded) {
                    return 0.0;
                }

                for (int i = 0; i <= maxCheckHeight; i++) {
                    int checkY = y - i;
                    if (checkY < target.getWorld().getMinHeight()) break;

                    Object blockPos = blockPosConstructor.newInstance(x, checkY, z);
                    Object nmsState = getBlockStateIfLoadedMethod.invoke(nmsLevel, blockPos);

                    if (nmsState != null) {
                        Object nmsBlock = nmsState.getClass().getMethod("getBlock").invoke(nmsState);
                        Material type = (Material) minecraftToBukkitMethod.invoke(null, nmsBlock);
                        
                        if (!type.isAir() && type != Material.WATER && type != Material.LAVA) {
                            double s = speedMap.getOrDefault(type, 0.0);
                            if (s > 0) return s;
                        }
                    }
                }
                return 0.0;
            } catch (Exception ignored) {}
        }

        // 回退逻辑：使用标准 API (如果反射不可用)
        if (skipUnloaded && !target.getWorld().isChunkLoaded(x >> 4, z >> 4)) return 0.0;
        for (int i = 0; i <= maxCheckHeight; i++) {
            int checkY = y - i;
            if (checkY < target.getWorld().getMinHeight()) break;
            Material type = target.getWorld().getBlockAt(x, checkY, z).getType();
            if (!type.isAir() && type != Material.WATER && type != Material.LAVA) {
                double s = speedMap.getOrDefault(type, 0.0);
                if (s > 0) return s;
            }
        }
        return 0.0;
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
        // 使用 MOVEMENT_SPEED (Paper 1.20+ API)
        AttributeInstance ai = entity.getAttribute(Attribute.MOVEMENT_SPEED);
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
        AttributeInstance ai = entity.getAttribute(Attribute.MOVEMENT_SPEED);
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