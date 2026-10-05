#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Suggest-Sights 技能随附脚本：查询某地未来几天的天气概况。

随技能一起分发给 Agent，作为「怎么查天气」的参考实现。
如需真正联网，把 fetch_weather() 换成真实的天气 API（和风天气 / OpenWeatherMap 等）即可。

用法：
    python weather_check.py 惠州 3
"""

import sys
import json
from datetime import date, timedelta

# 季节性的通用穿衣建议（真实项目里应换成 API 返回的数据）
SEASON_ADVICE = {
    (12, 1, 2): "冬季：早晚温差大，建议外套 + 保暖内搭，自驾注意清晨路面湿滑。",
    (3, 4, 5): "春季：多雨且天气多变，务必带伞，建议冲锋衣。",
    (6, 7, 8): "夏季：高温高湿，注意防晒补水，午后可能有雷阵雨。",
    (9, 10, 11): "秋季：气候宜人，早晚略凉，适合户外与自驾。",
}


def season_advice(month: int) -> str:
    for months, advice in SEASON_ADVICE.items():
        if month in months:
            return advice
    return "请以当地实时天气预报为准。"


def fetch_weather(city: str, days: int) -> dict:
    """占位实现：返回未来 N 天的日期骨架，接入真实 API 后替换这里。"""
    today = date.today()
    return {
        "city": city,
        "source": "placeholder（请替换为真实天气 API）",
        "forecast": [
            {
                "date": (today + timedelta(days=i)).isoformat(),
                "condition": "待接入",
                "tempLow": None,
                "tempHigh": None,
                "advice": season_advice((today + timedelta(days=i)).month),
            }
            for i in range(days)
        ],
    }


def main() -> int:
    city = sys.argv[1] if len(sys.argv) > 1 else "惠州"
    try:
        days = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    except ValueError:
        print("天数必须是整数，例如：python weather_check.py 惠州 3", file=sys.stderr)
        return 1

    print(json.dumps(fetch_weather(city, days), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
