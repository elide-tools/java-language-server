package org.javacs.example;

public class InlineMethodExample {
    static int twice(int x) {
        return x * 2;
    }

    int use(int n) {
        return twice(n + 1);
    }
}
