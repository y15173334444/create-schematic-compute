package io.github.y15173334444.create_schematic_compute.mixin;

import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 取 SoundEngine 私有的 channelAccess（CSC 音频引擎经 STREAMING 池挂自定义 AudioStream）。
 *  remap = false：NeoForge 1.21.1 生产环境即官方映射，无 reobf（仓内 mixin 同口径）。 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor(value = "channelAccess", remap = false)
    ChannelAccess csc$getChannelAccess();
}
