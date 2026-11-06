import com.combatsolver1.search.CardOrderPrior;

/**
 * W8-b 诊断入口：在「出牌先验开启」下跑 Oracle 报告分片。
 *
 * <p>用法：java PriorOracle 43-45
 * 与 SearchQuality --only 43-45 的唯一差别是先把 CardOrderPrior 打开，
 * 用于判断 W8 的漏优能否被排序先验修复（先验只引导不剪枝，见 W7-a/b）。
 * 纯诊断工具，不改评测逻辑、不写文件。
 */
public final class PriorOracle {
    private PriorOracle() { }

    public static void main(String[] args) throws Exception {
        CardOrderPrior.setEnabled(true);
        String[] forwarded;
        if (args.length > 0) {
            forwarded = new String[]{"--only", args[0]};
        } else {
            forwarded = new String[0];
        }
        SearchQuality.main(forwarded);
    }
}
