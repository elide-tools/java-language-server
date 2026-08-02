package org.javacs.example;

class InlayHintExample {
    int add(int first, int second) {
        return first + second;
    }

    int use() {
        var total = add(1, 2);
        return total;
    }
}
