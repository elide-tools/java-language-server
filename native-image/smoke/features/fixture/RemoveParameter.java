class RemoveParameter {
    private int compute(int a, int unused) {
        return a * 2;
    }

    int use() {
        return compute(5, 9);
    }
}
