package tools.jackson.spoof;

/**
 * A user class that sits in a {@code tools.jackson} package. A package name is not ownership: the engine
 * must treat it as a user class (task 288).
 */
public class Spoof {
    public String value() {
        return "invented";
    }
}
