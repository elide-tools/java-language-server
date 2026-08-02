class ReplaceConstructor {
    final int value;

    ReplaceConstructor(int value) {
        this.value = value;
    }

    ReplaceConstructor make() {
        return new ReplaceConstructor(42);
    }
}
