package org.javacs.example;

public class AddParameterExample {
    private int scale(int base) {
        return base;
    }

    int use() {
        return scale(10, 2);
    }
}
