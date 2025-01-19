package raccoonman.reterraforged.forge;

import com.mojang.serialization.Codec;
import net.dries007.tfc.TerraFirmaCraft;
import net.dries007.tfc.world.ChunkGeneratorExtension;
import net.dries007.tfc.world.TFCChunkGenerator;
import net.minecraft.core.Registry;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.metadata.PackMetadataGenerator;
import net.minecraft.data.worldgen.BootstapContext;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.client.data.RTFLanguageProvider;
import raccoonman.reterraforged.client.data.RTFTranslationKeys;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.forge.tfc.TFCChunkGeneratorData;
import raccoonman.reterraforged.forge.tfc.TFCCompatibleChunkGenerator;
import raccoonman.reterraforged.platform.RegistryUtil;
import raccoonman.reterraforged.platform.forge.RegistryUtilImpl;
import raccoonman.reterraforged.registries.RTFRegistries;

@Mod(RTFCommon.MOD_ID)
public class RTFForge {

    public RTFForge() {
		RTFCommon.LOGGER.info("Reterraforged Init!");

		Preset.CUSTOM_CHUNK_GENERATOR = (profile, ctx) -> {
			TFCChunkGeneratorData.bootstrap(profile, (BootstapContext<ChunkGenerator>) ctx);
		};
		Preset.CHUNK_GENERATOR_KEY = TFCChunkGeneratorData.CHUNK_GENERATOR_REGISTRY.getRegistryKey();

    	RTFCommon.bootstrap();

    	IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

    	if (FMLEnvironment.dist == Dist.CLIENT) {
    		modBus.addListener(RTFForgeClient::registerPresetEditors);
    	}
    	modBus.addListener(RTFForge::gatherData);

		RTFCommon.LOGGER.info("ModBus registering TFCCompatibleChunkGenerator");
		TFCChunkGeneratorData.CHUNK_GENERATOR_REGISTRY.register(modBus);
    	RegistryUtilImpl.register(modBus);

//		RegistryUtil.createDataRegistry(TFCCompatibleChunkGenerator.CHUNK_GENERATOR.getKey(), TFCCompatibleChunkGenerator.CODEC);
    }
    
    private static void gatherData(GatherDataEvent event) {
    	boolean includeClient = event.includeClient();
    	DataGenerator generator = event.getGenerator();
    	PackOutput output = generator.getPackOutput();
    	
    	generator.addProvider(includeClient, new RTFLanguageProvider.EnglishUS(output));
    	generator.addProvider(includeClient, PackMetadataGenerator.forFeaturePack(output, Component.translatable(RTFTranslationKeys.METADATA_DESCRIPTION)));
    }
}