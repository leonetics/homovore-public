package dev.leonetic.util;

import dev.leonetic.Homovore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * Reflection-based bridge into Litematica so we can query the schematic preview
 * without a compile-time dependency.
 */
public final class LitematicaSchematics {
    private static final String MOD_ID = "litematica";

    private static final String[] SCHEMATIC_WORLD_HANDLER_CLASSES = {
        "fi.dy.masa.litematica.world.SchematicWorldHandler",
        "litematica.world.SchematicWorldHandler"
    };

    private static final String[] DATA_MANAGER_CLASSES = {
        "fi.dy.masa.litematica.data.DataManager",
        "litematica.data.DataManager"
    };

    private static final String[] LAYER_RANGE_CLASSES = {
        "malilib.util.position.LayerRange",
        "fi.dy.masa.litematica.util.LayerRange",
        "litematica.util.position.LayerRange"
    };

    private static boolean attemptedInit;
    private static boolean hooksActive;

    private static MethodHandle getSchematicWorldHandle;
    private static MethodHandle getRenderLayerRangeHandle;
    private static MethodHandle isPositionWithinRangeHandle;
    private static MethodHandle dataManagerSupplierHandle;
    private static boolean renderLayerRangeNeedsReceiver;
    private static boolean layerRangeUsesBlockPos;

    private static boolean attemptedRangeBinding;
    private static boolean layerRangeSupported;

    // Bounding box support — verifies a position falls inside a schematic placement's sub-regions
    private static boolean boundingBoxSupported;
    private static MethodHandle getPlacementManagerHandle;
    private static boolean placementMgrNeedsReceiver;
    private static MethodHandle getAllPlacementsHandle;
    private static MethodHandle placementIsEnabledHandle;
    private static MethodHandle getBoxesWithinChunkHandle;
    private static Object requiredEnabledArg; // The RequiredEnabled enum value to pass to getBoxesWithinChunk (may be null if the method only takes 2 args)
    private static boolean getBoxesNeedsEnabledArg;
    private static Field boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ;

    private LitematicaSchematics() {}

    public static boolean isAvailable() {
        ensureInitialized();
        return hooksActive;
    }

    public static boolean isInstalled() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    public static boolean isMismatched(BlockPos pos, BlockState currentState) {
        return isMismatched(pos, currentState, false, false);
    }

    public static boolean isMismatched(BlockPos pos, BlockState currentState, boolean includeAir, boolean restrictToVisibleLayers) {
        BlockState schematicState = getSchematicState(pos, restrictToVisibleLayers);
        if (schematicState == null) return false;

        if (schematicState.isAir()) {
            if (!includeAir) return false;
            return !currentState.isAir();
        }

        return !schematicState.equals(currentState);
    }

    @Nullable
    public static BlockState getSchematicBlockState(BlockPos pos) {
        return getSchematicState(pos, true);
    }

    @Nullable
    public static BlockState getSchematicBlockState(BlockPos pos, boolean restrictToVisibleLayers) {
        return getSchematicState(pos, restrictToVisibleLayers);
    }

    /**
     * Returns {@code true} if the position falls inside a sub-region box of any
     * enabled schematic placement.  Uses {@code getBoxesWithinChunk} for precise
     * per-chunk sub-region checking.  If bounding-box support could not be
     * initialised, this returns {@code false} (fail-closed: don't nuke unknown positions).
     */
    public static boolean isInsideLoadedSchematicBox(BlockPos pos) {
        ensureInitialized();
        if (!hooksActive || !boundingBoxSupported) return false;
        try {
            Object mgr;
            if (placementMgrNeedsReceiver) {
                Object dm = dataManagerSupplierHandle != null ? dataManagerSupplierHandle.invoke() : null;
                if (dm == null) return false;
                mgr = getPlacementManagerHandle.invoke(dm);
            } else {
                mgr = getPlacementManagerHandle.invoke();
            }
            if (mgr == null) return false;

            List<?> placements = (List<?>) getAllPlacementsHandle.invoke(mgr);
            if (placements == null || placements.isEmpty()) return false;

            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;

            for (Object pl : placements) {
                if (placementIsEnabledHandle != null && !(boolean) placementIsEnabledHandle.invoke(pl)) continue;
                Object boxMap;
                try {
                    boxMap = getBoxesNeedsEnabledArg
                        ? getBoxesWithinChunkHandle.invoke(pl, chunkX, chunkZ, requiredEnabledArg)
                        : getBoxesWithinChunkHandle.invoke(pl, chunkX, chunkZ);
                } catch (java.lang.invoke.WrongMethodTypeException wmt) {
                    // Fallback: the method signature doesn't match what we expected.
                    // Try invoking with the extra arg if we didn't, or without it if we did.
                    if (!getBoxesNeedsEnabledArg) {
                        Homovore.LOGGER.warn("getBoxesWithinChunk needs more args than expected, attempting to resolve RequiredEnabled at runtime.", wmt);
                        resolveRequiredEnabledFromPlacement(pl);
                        boxMap = getBoxesNeedsEnabledArg
                            ? getBoxesWithinChunkHandle.invoke(pl, chunkX, chunkZ, requiredEnabledArg)
                            : null;
                    } else {
                        throw wmt;
                    }
                }
                if (boxMap == null) continue;
                // boxMap is ImmutableMap<String, IntBoundingBox>
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) boxMap;
                for (Object box : map.values()) {
                    if (boxContains(box, pos)) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            boundingBoxSupported = false;
            Homovore.LOGGER.warn("Failed to check Litematica placement bounds; disabling bound checks.", t);
            return false;
        }
    }

    /**
     * Runtime resolution of the RequiredEnabled enum by inspecting the actual object's class
     * method signatures. Called as a fallback if the init-time binding picked the wrong overload.
     */
    private static void resolveRequiredEnabledFromPlacement(Object placement) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> cls = placement.getClass();
            for (Method m : cls.getMethods()) {
                if (!"getBoxesWithinChunk".equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 3 && params[0] == int.class && params[1] == int.class && params[2].isEnum()) {
                    m.setAccessible(true);
                    getBoxesWithinChunkHandle = lookup.unreflect(m);
                    Class<?> enumClass = params[2];
                    Object[] enumConstants = enumClass.getEnumConstants();
                    requiredEnabledArg = null;
                    for (Object ec : enumConstants) {
                        if (ec.toString().contains("ENABLED")) { requiredEnabledArg = ec; break; }
                    }
                    if (requiredEnabledArg == null && enumConstants.length > 0) {
                        requiredEnabledArg = enumConstants[0];
                    }
                    getBoxesNeedsEnabledArg = true;
                    Homovore.LOGGER.info("Resolved getBoxesWithinChunk at runtime: {} with arg {} from {}",
                        m, requiredEnabledArg, enumClass.getName());
                    return;
                }
            }
            Homovore.LOGGER.warn("Could not resolve getBoxesWithinChunk with RequiredEnabled at runtime on {}",
                cls.getName());
        } catch (Throwable t) {
            Homovore.LOGGER.warn("Error resolving RequiredEnabled at runtime", t);
        }
    }

    private static boolean boxContains(Object box, BlockPos pos) throws Throwable {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return x >= boxMinX.getInt(box) && x <= boxMaxX.getInt(box)
            && y >= boxMinY.getInt(box) && y <= boxMaxY.getInt(box)
            && z >= boxMinZ.getInt(box) && z <= boxMaxZ.getInt(box);
    }

    @Nullable
    private static BlockState getSchematicState(BlockPos pos, boolean restrictToVisibleLayers) {
        Object world = getSchematicWorld();
        if (world == null) return null;

        if (restrictToVisibleLayers && layerRangeSupported) {
            Object range = getLayerRange();
            if (range != null && !isWithinRange(range, pos)) return null;
        }

        if (!(world instanceof Level level)) {
            disableHooks("use schematic world", new IllegalStateException("Unexpected schematic world type: " + world.getClass().getName()));
            return null;
        }

        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;

        return level.getBlockState(pos);
    }

    @Nullable
    private static Object getSchematicWorld() {
        ensureInitialized();
        if (!hooksActive) return null;

        try {
            return getSchematicWorldHandle.invoke();
        } catch (Throwable t) {
            disableHooks("query schematic world", t);
            return null;
        }
    }

    @Nullable
    private static Object getLayerRange() {
        if (!layerRangeSupported) return null;
        try {
            Object range;
            if (renderLayerRangeNeedsReceiver) {
                Object dm = dataManagerSupplierHandle != null ? dataManagerSupplierHandle.invoke() : null;
                if (dm == null) return null;
                range = getRenderLayerRangeHandle.invoke(dm);
            } else {
                range = getRenderLayerRangeHandle.invoke();
            }

            if (range == null) return null;
            if (isPositionWithinRangeHandle == null && !bindRangeInspector(range)) return null;
            return range;
        } catch (Throwable t) {
            layerRangeSupported = false;
            Homovore.LOGGER.warn("Failed to query Litematica render layer range, continuing without range filtering.", t);
            return null;
        }
    }

    private static boolean isWithinRange(Object range, BlockPos pos) {
        if (!layerRangeSupported) return true;
        if (isPositionWithinRangeHandle == null && !bindRangeInspector(range)) return true;
        try {
            if (layerRangeUsesBlockPos) return (boolean) isPositionWithinRangeHandle.invoke(range, pos);
            return (boolean) isPositionWithinRangeHandle.invoke(range, pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable t) {
            layerRangeSupported = false;
            Homovore.LOGGER.warn("Failed to evaluate Litematica render range, disabling range checks.", t);
            return true;
        }
    }

    private static boolean bindRangeInspector(Object range) {
        if (attemptedRangeBinding) return isPositionWithinRangeHandle != null;
        attemptedRangeBinding = true;

        if (range == null) {
            layerRangeSupported = false;
            return false;
        }

        try {
            Method method = null;
            Class<?> cls = range.getClass();

            try {
                method = cls.getMethod("isPositionWithinRange", BlockPos.class);
                layerRangeUsesBlockPos = true;
            } catch (NoSuchMethodException ignored) {
                layerRangeUsesBlockPos = false;
            }

            if (method == null) {
                method = cls.getMethod("isPositionWithinRange", int.class, int.class, int.class);
            }

            if (method == null) {
                layerRangeSupported = false;
                Homovore.LOGGER.warn("Litematica render layer range does not expose isPositionWithinRange; disabling range filtering.");
                return false;
            }

            method.setAccessible(true);
            isPositionWithinRangeHandle = MethodHandles.lookup().unreflect(method);
            return true;
        } catch (Throwable t) {
            layerRangeSupported = false;
            Homovore.LOGGER.warn("Failed to bind Litematica render layer range inspector; disabling range filtering.", t);
            return false;
        }
    }

    private static synchronized void ensureInitialized() {
        if (attemptedInit) return;
        attemptedInit = true;

        if (!isInstalled()) return;

        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> handlerClass = loadRequiredClass(SCHEMATIC_WORLD_HANDLER_CLASSES);
            Class<?> dataManagerClass = loadRequiredClass(DATA_MANAGER_CLASSES);

            Method getSchematicWorld = handlerClass.getDeclaredMethod("getSchematicWorld");
            getSchematicWorld.setAccessible(true);
            getSchematicWorldHandle = lookup.unreflect(getSchematicWorld);

            Method getRenderLayerRange = dataManagerClass.getDeclaredMethod("getRenderLayerRange");
            getRenderLayerRange.setAccessible(true);
            renderLayerRangeNeedsReceiver = !Modifier.isStatic(getRenderLayerRange.getModifiers());
            if (renderLayerRangeNeedsReceiver) {
                try {
                    Method supplier = dataManagerClass.getDeclaredMethod("getInstance");
                    supplier.setAccessible(true);
                    dataManagerSupplierHandle = lookup.unreflect(supplier);
                } catch (NoSuchMethodException ignored) {}
            }
            getRenderLayerRangeHandle = lookup.unreflect(getRenderLayerRange);
            layerRangeSupported = true;

            Class<?> layerRangeClass = loadOptionalClass(LAYER_RANGE_CLASSES);
            if (layerRangeClass != null) {
                Method isPositionWithinRange = null;
                try {
                    isPositionWithinRange = layerRangeClass.getMethod("isPositionWithinRange", BlockPos.class);
                    layerRangeUsesBlockPos = true;
                } catch (NoSuchMethodException ignored) {
                    layerRangeUsesBlockPos = false;
                }

                if (isPositionWithinRange == null) {
                    isPositionWithinRange = layerRangeClass.getMethod("isPositionWithinRange", int.class, int.class, int.class);
                }

                isPositionWithinRangeHandle = lookup.unreflect(isPositionWithinRange);
                attemptedRangeBinding = true;
            }

            hooksActive = true;

            // Try to bind bounding box checking for placement-aware filtering.
            // Uses SchematicPlacement.getBoxesWithinChunk(int, int) → ImmutableMap<String, IntBoundingBox>.
            try {
                Method getPlMgr = findMethod(dataManagerClass, "getSchematicPlacementManager");
                if (getPlMgr == null)
                    throw new NoSuchMethodException("getSchematicPlacementManager not found on " + dataManagerClass.getName()
                        + "; available: " + listMethodNames(dataManagerClass));
                getPlMgr.setAccessible(true);
                placementMgrNeedsReceiver = !Modifier.isStatic(getPlMgr.getModifiers());
                getPlacementManagerHandle = lookup.unreflect(getPlMgr);

                // Ensure we can obtain a DataManager instance if the method needs one
                if (placementMgrNeedsReceiver && dataManagerSupplierHandle == null) {
                    try {
                        Method supplier = dataManagerClass.getDeclaredMethod("getInstance");
                        supplier.setAccessible(true);
                        dataManagerSupplierHandle = lookup.unreflect(supplier);
                    } catch (NoSuchMethodException ignored) {}
                }

                Class<?> plMgrClass = getPlMgr.getReturnType();
                Method allPl = findMethod(plMgrClass,
                    "getAllSchematicsPlacements", "getSchematicPlacements", "getAllPlacements");
                if (allPl == null)
                    throw new NoSuchMethodException("No getAllPlacements variant found on " + plMgrClass.getName()
                        + "; available: " + listMethodNames(plMgrClass));
                allPl.setAccessible(true);
                getAllPlacementsHandle = lookup.unreflect(allPl);

                // Find the placement class. Try the list's generic type first, then known class names.
                Class<?> plClass = loadOptionalClass(
                    "fi.dy.masa.litematica.schematic.placement.SchematicPlacement",
                    "litematica.schematic.placement.SchematicPlacement");

                // If the known class names don't resolve, try to infer from the first element at runtime.
                // For now just log a clear message.
                if (plClass == null)
                    throw new ClassNotFoundException("Could not find SchematicPlacement class");

                try {
                    Method isEn = findMethod(plClass, "isEnabled");
                    if (isEn != null) {
                        isEn.setAccessible(true);
                        placementIsEnabledHandle = lookup.unreflect(isEn);
                    }
                } catch (Throwable ignored) {}

                // Search the full class hierarchy for getBoxesWithinChunk.
                // Prefer the 3-param (int, int, RequiredEnabled) overload over 2-param (int, int).
                Method getBoxes = null;
                Method getBoxesFallback = null;
                for (Method m : plClass.getMethods()) {
                    if (!"getBoxesWithinChunk".equals(m.getName())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[0] == int.class && p[1] == int.class && p[2].isEnum()) {
                        getBoxes = m; // Prefer 3-param
                        break;
                    }
                    if (p.length == 2 && p[0] == int.class && p[1] == int.class) {
                        getBoxesFallback = m;
                    }
                }
                // Also search declared methods on the hierarchy
                if (getBoxes == null) {
                    for (Class<?> c = plClass; c != null && c != Object.class; c = c.getSuperclass()) {
                        for (Method m : c.getDeclaredMethods()) {
                            if (!"getBoxesWithinChunk".equals(m.getName())) continue;
                            Class<?>[] p = m.getParameterTypes();
                            if (p.length == 3 && p[0] == int.class && p[1] == int.class && p[2].isEnum()) {
                                getBoxes = m;
                                break;
                            }
                            if (getBoxesFallback == null && p.length == 2 && p[0] == int.class && p[1] == int.class) {
                                getBoxesFallback = m;
                            }
                        }
                        if (getBoxes != null) break;
                    }
                }
                if (getBoxes == null) getBoxes = getBoxesFallback;
                if (getBoxes == null)
                    throw new NoSuchMethodException("No getBoxesWithinChunk found on " + plClass.getName()
                        + " or its supertypes; available: " + listMethodNames(plClass));
                Homovore.LOGGER.info("Bound getBoxesWithinChunk: {} (params: {})",
                    getBoxes, Arrays.toString(getBoxes.getParameterTypes()));
                getBoxes.setAccessible(true);
                getBoxesWithinChunkHandle = lookup.unreflect(getBoxes);

                // Check if getBoxesWithinChunk has a 3rd RequiredEnabled parameter
                Class<?>[] paramTypes = getBoxes.getParameterTypes();
                if (paramTypes.length >= 3 && paramTypes[2].isEnum()) {
                    // Resolve the enum constant — use PLACEMENT_ENABLED or the first constant
                    Class<?> enumClass = paramTypes[2];
                    Object[] enumConstants = enumClass.getEnumConstants();
                    requiredEnabledArg = null;
                    for (Object ec : enumConstants) {
                        if (ec.toString().contains("ENABLED")) {
                            requiredEnabledArg = ec;
                            break;
                        }
                    }
                    if (requiredEnabledArg == null && enumConstants.length > 0) {
                        requiredEnabledArg = enumConstants[0];
                    }
                    getBoxesNeedsEnabledArg = true;
                    Homovore.LOGGER.info("getBoxesWithinChunk requires RequiredEnabled arg: {} (from {})",
                        requiredEnabledArg, enumClass.getName());
                } else {
                    getBoxesNeedsEnabledArg = false;
                }

                // Bind IntBoundingBox fields — the box type is the map's value type
                Class<?> boxClass = loadOptionalClass(
                    "fi.dy.masa.malilib.util.IntBoundingBox",
                    "malilib.util.IntBoundingBox",
                    "malilib.util.position.IntBoundingBox");
                if (boxClass != null) {
                    boxMinX = findField(boxClass, "minX");
                    boxMinY = findField(boxClass, "minY");
                    boxMinZ = findField(boxClass, "minZ");
                    boxMaxX = findField(boxClass, "maxX");
                    boxMaxY = findField(boxClass, "maxY");
                    boxMaxZ = findField(boxClass, "maxZ");
                    if (boxMinX != null && boxMaxX != null && boxMinY != null && boxMaxY != null && boxMinZ != null && boxMaxZ != null) {
                        boundingBoxSupported = true;
                        Homovore.LOGGER.info("Litematica bounding box support initialized (using getBoxesWithinChunk on {}).", plClass.getName());
                    } else {
                        throw new NoSuchFieldException("Missing fields on " + boxClass.getName()
                            + "; available: " + listFieldNames(boxClass));
                    }
                } else {
                    throw new ClassNotFoundException("Could not find IntBoundingBox class");
                }
            } catch (Throwable t2) {
                boundingBoxSupported = false;
                Homovore.LOGGER.warn("Could not bind Litematica placement bounding box API; " +
                    "extra-block filtering will not be limited to placement bounds.", t2);
            }

            Homovore.LOGGER.info("Initialized Litematica hooks for schematic-aware features.");
        } catch (Throwable t) {
            disableHooks("initialize", t);
        }
    }

    /**
     * Searches for a method by name across the entire class hierarchy (declared + inherited).
     */
    @Nullable
    private static Method findMethod(Class<?> cls, String... names) {
        for (String name : names) {
            // First try all public methods (includes inherited)
            for (Method m : cls.getMethods()) {
                if (m.getName().equals(name)) return m;
            }
            // Then try declared (private/protected) on each level of the hierarchy
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name)) return m;
                }
            }
        }
        return null;
    }

    /**
     * Searches for a field by name across the entire class hierarchy.
     */
    @Nullable
    private static Field findField(Class<?> cls, String name) {
        // Try public fields first (includes inherited public fields)
        try {
            return cls.getField(name);
        } catch (NoSuchFieldException ignored) {}
        // Walk hierarchy for non-public fields
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /** Diagnostic: lists all method names on a class and its supertypes. */
    private static String listMethodNames(Class<?> cls) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (Method m : cls.getMethods()) names.add(m.getName());
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) names.add(m.getName());
        }
        return names.toString();
    }

    /** Diagnostic: lists all field names on a class and its supertypes. */
    private static String listFieldNames(Class<?> cls) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (Field f : cls.getFields()) names.add(f.getName());
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) names.add(f.getName());
        }
        return names.toString();
    }

    private static void disableHooks(String stage, Throwable t) {
        hooksActive = false;
        layerRangeSupported = false;
        Homovore.LOGGER.warn("Failed to {} Litematica hooks. Disabling schematic integration.", stage, t);
    }

    private static Class<?> loadRequiredClass(String... candidates) throws ClassNotFoundException {
        Class<?> clazz = loadOptionalClass(candidates);
        if (clazz == null) {
            throw new ClassNotFoundException("Could not find any of the classes " + Arrays.toString(candidates));
        }
        return clazz;
    }

    @Nullable
    private static Class<?> loadOptionalClass(String... candidates) {
        for (String name : candidates) {
            if (name == null || name.isEmpty()) continue;
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }
}
