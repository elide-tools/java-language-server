package org.javacs.example;

public class SymbolsWithRecord {
    record Coordinate(int x, int y) {}

    void useRecord() {
        var c = new Coordinate(1, 2);
    }
}
