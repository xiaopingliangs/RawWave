package com.rawwave.core;

/** Explicit user inputs, in SDK units. Stored with the template and immutable session plan. */
public record BiaProfile(float heightCm, float weightKg, int age, int gender) {
    public BiaProfile {
        if (!Float.isFinite(heightCm) || heightCm < 20 || heightCm > 300)
            throw new IllegalArgumentException("身高需要20至300厘米");
        if (!Float.isFinite(weightKg) || weightKg < 2 || weightKg > 500)
            throw new IllegalArgumentException("体重需要2至500千克");
        if (age < 0 || age > 200) throw new IllegalArgumentException("年龄需要0至200岁");
        if (gender != 0 && gender != 1) throw new IllegalArgumentException("请选择三星体成分算法所需的性别");
    }
}
