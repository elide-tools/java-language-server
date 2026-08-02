package org.javacs.example;

public class RemoveParameterExample {
    private int compute(int a, int unused) {
        return a * 2;
    }

    int use() {
        return compute(5, 9);
    }
}
