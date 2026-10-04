package tripPlannerAgent.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * author: Imooc
 * description: 行程规划用的计算工具
 * date: 2026
 *
 * <p>课程原版的 {@code Calculate#sum()} 是一个无参、无返回值的空方法，
 * 注册进去之后大模型即使调用它也拿不到任何东西。这里补齐成真正可用的工具。</p>
 *
 * <p>注意：工具方法的参数名会被写入 JSON Schema，所以编译器必须开启 {@code -parameters}
 * （已在父 pom 的 maven-compiler-plugin 里统一配置），
 * 否则参数名会退化成 arg0/arg1，大模型无法正确调用。</p>
 */
public class Calculate {

    @Tool(description = "求和计算：返回两个数字之和")
    public String sum(@ToolParam(name = "a", description = "第一个加数") double a,
                      @ToolParam(name = "b", description = "第二个加数") double b) {
        return format(a + b);
    }

    @Tool(description = "估算旅行总预算：人均每日花费 × 人数 × 天数")
    public String travelBudget(@ToolParam(name = "perPersonPerDay", description = "人均每日花费，单位元")
                              double perPersonPerDay,
                              @ToolParam(name = "people", description = "出行人数") int people,
                              @ToolParam(name = "days", description = "旅行天数") int days) {

        if (people <= 0 || days <= 0) {
            return "参数不合法：人数和天数都必须大于 0";
        }
        double total = perPersonPerDay * people * days;
        return "总预算约 %s 元（人均 %.2f 元/天 × %d 人 × %d 天）"
                .formatted(format(total), perPersonPerDay, people, days);
    }

    @Tool(description = "按总预算与天数计算每日平均可用预算")
    public String dailyBudget(@ToolParam(name = "totalBudget", description = "总预算，单位元") double totalBudget,
                              @ToolParam(name = "days", description = "旅行天数") int days) {
        if (days <= 0) {
            return "参数不合法：天数必须大于 0";
        }
        return "每日平均可用预算约 %s 元".formatted(format(totalBudget / days));
    }

    @Tool(description = "按油价、里程与百公里油耗估算自驾油费")
    public String fuelCost(@ToolParam(name = "distanceKm", description = "总里程，单位公里") double distanceKm,
                           @ToolParam(name = "fuelConsumptionPer100Km", description = "百公里油耗，单位升")
                           double fuelConsumptionPer100Km,
                           @ToolParam(name = "fuelPricePerLiter", description = "每升油价，单位元")
                           double fuelPricePerLiter) {
        double cost = distanceKm / 100.0 * fuelConsumptionPer100Km * fuelPricePerLiter;
        return "预计油费约 %s 元（%.1f 公里 × %.1f L/100km × %.2f 元/L）"
                .formatted(format(cost), distanceKm, fuelConsumptionPer100Km, fuelPricePerLiter);
    }

    private static String format(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
