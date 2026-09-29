package neko;

/**
 * 全模组共享的常量（不引用任何 Minecraft 类型，跨版本通用）。
 *
 * <p>mod id / 分类名这类「每个版本都一样的字符串」集中在这里，
 * 各版本实现（{@code AutoMendingMod} 等）直接引用，避免在多个文件里重复写死。</p>
 */
public final class NekoConstants {
    /** 模组 id（也是资源命名空间、存档 id 的命名空间、mixin 配置文件名的前缀） */
    public static final String MOD_ID = "carpet_neko_addition";

    /** /carpet 里的自定义分类名（两条规则共用同一分类） */
    public static final String CATEGORY_NEKO = "neko";

    private NekoConstants() {
    }
}
