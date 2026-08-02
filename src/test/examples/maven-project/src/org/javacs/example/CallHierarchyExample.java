package org.javacs.example;

class CallHierarchyExample {
    void target() {
        helper();
    }

    void caller() {
        target();
    }

    void helper() {
    }
}
