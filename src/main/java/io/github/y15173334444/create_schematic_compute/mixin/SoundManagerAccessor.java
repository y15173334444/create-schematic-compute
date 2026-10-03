package io.github.y15173334444.create_schematic_compute.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 取 SoundManager 私有的 soundEngine（CSC 音频引擎要下探到 ChannelAccess）。
 *  remap = false：NeoForge 1.21.1 生产环境即官方映射，无 reobf（仓内 mixin 同口径）。 */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {
    @Accessor(value = "soundEngine", remap = false)
    SoundEngine csc$getSoundEngine();
}
