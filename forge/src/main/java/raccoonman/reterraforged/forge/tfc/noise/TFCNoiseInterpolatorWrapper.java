package raccoonman.reterraforged.forge.tfc.noise;

import java.lang.reflect.Field;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class TFCNoiseInterpolatorWrapper {

    public static TFCNoiseInterpolatorWrapper registerWith(NoiseChunk noiseChunk, DensityFunction function) {
        return new TFCNoiseInterpolatorWrapper(noiseChunk, function);
    }

    private static NoiseChunk.NoiseInterpolator getNoiseInterpolator(NoiseChunk noiseChunk, DensityFunction function) {
        try {
            Constructor<NoiseChunk.NoiseInterpolator> constructor =
                    NoiseChunk.NoiseInterpolator.class.getDeclaredConstructor(NoiseChunk.class, DensityFunction.class);

            // Make the constructor accessible
            constructor.setAccessible(true);
            return constructor.newInstance(noiseChunk, function);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException |
                 InvocationTargetException e) {
            throw new RuntimeException("Failed to access/instantiate NoiseInterpolator", e);
        }
    }

    protected final NoiseChunk noiseChunk;
    protected final NoiseChunk.NoiseInterpolator interpolator;

    TFCNoiseInterpolatorWrapper(NoiseChunk chunk, DensityFunction function) {
        this.noiseChunk = chunk;
        this.interpolator = getNoiseInterpolator(chunk, function);
    }

    public double sample() {
        double inCellX = InterpolatorReflectionHelper.getPrivateIntField(this.noiseChunk, "inCellX");
        double inCellY = InterpolatorReflectionHelper.getPrivateIntField(this.noiseChunk, "inCellY");
        double inCellZ = InterpolatorReflectionHelper.getPrivateIntField(this.noiseChunk, "inCellZ");
        double cellWidth = InterpolatorReflectionHelper.getPrivateIntField(this.noiseChunk, "cellWidth");
        double cellHeight = InterpolatorReflectionHelper.getPrivateIntField(this.noiseChunk, "cellHeight");

        double noise000 = InterpolatorReflectionHelper.getPrivateDoubleField(this.interpolator, "noise000");
        double noise100 = InterpolatorReflectionHelper.getPrivateDoubleField(this.interpolator, "noise100");
        double noise010 = InterpolatorReflectionHelper.getPrivateDoubleField(this.interpolator, "noise010");
        double noise110 = InterpolatorReflectionHelper.getPrivateDoubleField(this.interpolator, "noise110");
        double noise001 = InterpolatorReflectionHelper.getPrivateDoubleField(this.interpolator, "noise001");
        double noise101 = InterpolatorReflectionHelper.getPrivateDoubleField(this.interpolator, "noise101");
        double noise011 = InterpolatorReflectionHelper.getPrivateDoubleField(this.interpolator, "noise011");
        double noise111 = InterpolatorReflectionHelper.getPrivateDoubleField(this.interpolator, "noise111");

        return Mth.lerp3(
                inCellX / cellWidth,
                inCellY / cellHeight,
                inCellZ / cellWidth,
                noise000, noise100, noise010, noise110,
                noise001, noise101, noise011, noise111
        );
    }

    static class InterpolatorReflectionHelper {
        public static double getPrivateDoubleField(Object obj, String fieldName) {
            try {
                Field field = obj.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.getDouble(obj);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Failed to access field: " + fieldName, e);
            }
        }

        public static int getPrivateIntField(Object obj, String fieldName) {
            try {
                Field field = obj.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.getInt(obj);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Failed to access field: " + fieldName, e);
            }
        }
    }


}
