package neko.automending;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * 从 assets/&lt;modid&gt;/lang/&lt;lang&gt;.json 读取规则名与描述的翻译，
 * 交给 Carpet 的 {@code canHasTranslations}。
 */
public final class TranslationLoader {
    private static final Gson GSON = new Gson();

    private TranslationLoader() {
    }

    public static Map<String, String> load(String lang) {
        String path = "assets/" + AutoMendingMod.MOD_ID + "/lang/" + lang + ".json";

        try (InputStream in = TranslationLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Map<String, String> map = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {
                }.getType());
                return map != null ? map : Collections.emptyMap();
            }
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
