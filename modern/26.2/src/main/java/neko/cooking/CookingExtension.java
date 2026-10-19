package neko.cooking;

import carpet.CarpetExtension;
import carpet.CarpetServer;

import neko.automending.AutoMendingMod;
import neko.automending.TranslationLoader;

import java.util.Map;

/**
 * 熔炉玩法的 Carpet 扩展。
 *
 * <p>与 {@code AutoMendingExtension} / {@code VoidWarehouseExtension} 并存，各自注册自己的规则类；
 * 这样这几条规则之间没有任何耦合，加一条新玩法不用动已有代码。</p>
 */
public class CookingExtension implements CarpetExtension {
    private static final CookingExtension INSTANCE = new CookingExtension();

    private CookingExtension() {
    }

    public static void init() {
        CarpetServer.manageExtension(INSTANCE);
    }

    @Override
    public String version() {
        return AutoMendingMod.MOD_VERSION;
    }

    @Override
    public void onGameStarted() {
        CarpetServer.settingsManager.parseSettingsClass(CookingSettings.class);
    }

    /**
     * 规则名与描述的翻译。
     *
     * <p>Carpet 会遍历<b>每一个</b>已注册扩展的 {@code canHasTranslations} 并合并结果
     * （{@code Translations#updateLanguage} 字节码实证），所以这里和
     * {@code AutoMendingExtension} 返回同一份表也不会冲突。仍然显式覆写一遍：
     * 哪天真把自动修补那条线摘出去，翻译不会跟着一起消失。</p>
     */
    @Override
    public Map<String, String> canHasTranslations(String lang) {
        return TranslationLoader.load(lang);
    }
}
