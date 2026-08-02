package org.javacs.example;

class SemanticTokensExample {
    int field;

    int compute(int param) {
        int local = field + param;
        return helper(local);
    }

    int helper(int value) {
        return value;
    }
}
