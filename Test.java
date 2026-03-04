import org.bukkit.block.banner.PatternType;
public class Test {
    public static void main(String[] args) {
        for(PatternType pt : PatternType.values()) {
            System.out.println(pt.name());
        }
    }
}
