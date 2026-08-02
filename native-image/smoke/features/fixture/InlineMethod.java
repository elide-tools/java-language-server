class InlineMethod {
    static int twice(int x) {
        return x * 2;
    }

    int use(int n) {
        return twice(n + 1);
    }
}
