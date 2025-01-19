package raccoonman.reterraforged.data.worldgen.preset.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.apache.logging.log4j.Level;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.compat.terrablender.TBNoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.PresetBiomeData;
import raccoonman.reterraforged.data.worldgen.preset.PresetBiomeModifierData;
import raccoonman.reterraforged.data.worldgen.preset.PresetConfiguredCarvers;
import raccoonman.reterraforged.data.worldgen.preset.PresetConfiguredFeatures;
import raccoonman.reterraforged.data.worldgen.preset.PresetDimensionTypes;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseData;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseGeneratorSettings;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.PresetPlacedFeatures;
import raccoonman.reterraforged.data.worldgen.preset.PresetStructureRuleData;
import raccoonman.reterraforged.registries.RTFRegistries;

import javax.sound.midi.Patch;
import java.util.function.BiConsumer;
import java.util.function.Consumer;


public record Preset(WorldSettings world, SurfaceSettings surface, CaveSettings caves, ClimateSettings climate, TerrainSettings terrain, RiverSettings rivers, FilterSettings filters, StructureSettings structures, MiscellaneousSettings miscellaneous) {
	public static BiConsumer<Preset, BootstapContext<?>> CUSTOM_CHUNK_GENERATOR;
	public static ResourceKey CHUNK_GENERATOR_KEY;

	public static final Codec<Preset> DIRECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		WorldSettings.CODEC.fieldOf("world").forGetter(Preset::world),
		SurfaceSettings.CODEC.optionalFieldOf("surface", new SurfaceSettings(new SurfaceSettings.Erosion(30, 140, 40, 95, 0.65F, 0.475F, 0.4F))).forGetter(Preset::surface),
		CaveSettings.CODEC.optionalFieldOf("caves", new CaveSettings(0.0F, 1.5625F, 1.0F, 1.0F, 1.0F, 0.14285715F, 0.07F, 0.02F, true, false)).forGetter(Preset::caves),
		ClimateSettings.CODEC.fieldOf("climate").forGetter(Preset::climate),
		TerrainSettings.CODEC.fieldOf("terrain").forGetter(Preset::terrain),
		RiverSettings.CODEC.fieldOf("rivers").forGetter(Preset::rivers),
		FilterSettings.CODEC.fieldOf("filters").forGetter(Preset::filters),
		StructureSettings.CODEC.fieldOf("structures").forGetter(Preset::structures),
		MiscellaneousSettings.CODEC.fieldOf("miscellaneous").forGetter(Preset::miscellaneous)
	).apply(instance, Preset::new));
	
	@Deprecated
	public static final ResourceKey<Preset> KEY = RTFRegistries.createKey(RTFRegistries.PRESET, "preset");
	
	public Preset copy() {
		return new Preset(this.world.copy(), this.surface.copy(), this.caves.copy(), this.climate.copy(), this.terrain.copy(), this.rivers.copy(), this.filters.copy(), this.structures.copy(), this.miscellaneous.copy());
	}

	public HolderLookup.Provider buildPatch(RegistryAccess registries) {
		RTFCommon.LOGGER.info("Building patch...");
		RegistrySetBuilder builder = new RegistrySetBuilder();
		this.addPatch(builder, RTFRegistries.PRESET, (preset, ctx) -> ctx.register(KEY, preset));
		this.addPatch(builder, RTFRegistries.NOISE, PresetNoiseData::bootstrap);
		this.addPatch(builder, RTFRegistries.BIOME_MODIFIER, PresetBiomeModifierData::bootstrap);
		this.addPatch(builder, RTFRegistries.STRUCTURE_RULE, PresetStructureRuleData::bootstrap);
		this.addPatch(builder, Registries.CONFIGURED_FEATURE, (preset, ctx) -> {
			PresetConfiguredFeatures.bootstrap(preset, ctx);
		});
		this.addPatch(builder, Registries.CONFIGURED_CARVER, (preset, ctx) -> {
			PresetConfiguredCarvers.bootstrap(preset, ctx);	
		});
		this.addPatch(builder, Registries.PLACED_FEATURE, PresetPlacedFeatures::bootstrap);
		this.addPatch(builder, Registries.BIOME, PresetBiomeData::bootstrap);
		this.addPatch(builder, Registries.DIMENSION_TYPE, PresetDimensionTypes::bootstrap);
		this.addPatch(builder, Registries.DENSITY_FUNCTION, (preset, ctx) -> {
			PresetNoiseRouterData.bootstrap(preset, ctx);
			TBNoiseRouterData.bootstrap(ctx);
		});
		this.addPatch(builder, Registries.NOISE_SETTINGS, (preset, ctx) -> {
			PresetNoiseGeneratorSettings.bootstrap(preset, ctx);
			CUSTOM_CHUNK_GENERATOR.accept(preset, ctx);
		});
		return builder.buildPatch(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), registries);
	}
	
	private <T> void addPatch(RegistrySetBuilder builder, ResourceKey<? extends Registry<T>> key, Patch<T> patch) {
    	builder.add(key, (ctx) -> {
    		patch.apply(this, ctx);
    	});
    }
    
	private interface Patch<T> {
        void apply(Preset preset, BootstapContext<T> ctx);
	}
}
