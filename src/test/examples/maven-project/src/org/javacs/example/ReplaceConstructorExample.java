package org.javacs.example;

public class ReplaceConstructorExample {
    final int value;

    public ReplaceConstructorExample(int value) {
        this.value = value;
    }

    ReplaceConstructorExample make() {
        return new ReplaceConstructorExample(42);
    }
}
