package org.javacs.example;

interface Greeting {
    String greet();
}

public class MissingOverride implements Greeting {
    public String greet() {
        return "hi";
    }
}
