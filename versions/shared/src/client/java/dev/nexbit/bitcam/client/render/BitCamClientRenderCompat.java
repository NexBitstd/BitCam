package dev.nexbit.bitcam.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

public final class BitCamClientRenderCompat {
    private BitCamClientRenderCompat() {
    }

    public static DynamicTexture createDynamicTexture(String debugName, int width, int height, boolean useStb) {
        //#if MC>=12105
        return new DynamicTexture(() -> debugName, width, height, useStb);
        //#else
        //$$ return new DynamicTexture(width, height, useStb);
        //#endif
    }

    public static void setPixelAbgr(NativeImage image, int x, int y, int abgr) {
        //#if MC>=12105
        image.setPixelABGR(x, y, abgr);
        //#elseif MC>=12102
        //$$ image.setPixel(x, y, abgr);
        //#else
        //$$ image.setPixelRGBA(x, y, abgr);
        //#endif
    }

    public static void setPixel(NativeImage image, int x, int y, int color) {
        //#if MC>=12102
        image.setPixel(x, y, color);
        //#else
        //$$ image.setPixelRGBA(x, y, color);
        //#endif
    }

    public static int getPixel(NativeImage image, int x, int y) {
        //#if MC>=12102
        return image.getPixel(x, y);
        //#else
        //$$ return image.getPixelRGBA(x, y);
        //#endif
    }
}
