class Geometry {
    Shape shape;
    String name;

    Geometry(Shape shape, String name) {
        this.shape = shape;
        this.name = name;
    }

    double describe(Shape s) {
        double a = s.area();
        return a;
    }

    double total() {
        double sum = 0.0;
        sum = sum + describe(shape);
        sum = sum + describe(shape);
        return sum;
    }
}
