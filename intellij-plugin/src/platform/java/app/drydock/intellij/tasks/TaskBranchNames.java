package app.drydock.intellij.tasks;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/** Local, task-independent names for branches created by the New task dialog. */
final class TaskBranchNames {

    private static final String[] MOODS = {
            "bouncy", "brave", "breezy", "bright", "brisk", "bubbly", "calm", "cheerful",
            "clever", "cosmic", "cozy", "curious", "daring", "dashing", "dreamy", "eager",
            "fancy", "fearless", "festive", "friendly", "gentle", "giddy", "gleeful", "glowing",
            "graceful", "happy", "hopeful", "jaunty", "jolly", "jovial", "joyful", "keen",
            "kind", "lively", "lucky", "mellow", "merry", "mighty", "nimble", "nifty",
            "patient", "peppy", "perky", "playful", "plucky", "proud", "quirky", "quiet",
            "radiant", "serene", "silly", "sleepy", "sneaky", "snug", "spry", "sunny",
            "swift", "tidy", "tranquil", "upbeat", "valiant", "wily", "wise", "zippy"
    };

    private static final String[] TEXTURES = {
            "amber", "apricot", "azure", "bronze", "copper", "coral", "cotton", "crimson",
            "dappled", "dewy", "emerald", "feathery", "fluffy", "frosty", "golden", "indigo",
            "ivory", "jade", "leafy", "lilac", "lunar", "minty", "misty", "mossy",
            "peachy", "pearly", "rosy", "sandy", "silky", "silver", "starry", "velvet"
    };

    private static final String[] ANIMALS = {
            "alpaca", "badger", "beaver", "beetle", "bison", "bobcat", "buffalo", "bunny",
            "butterfly", "capybara", "caribou", "chameleon", "cheetah", "chipmunk", "corgi", "crane",
            "cricket", "dolphin", "dormouse", "dragonfly", "duck", "egret", "elephant", "falcon",
            "ferret", "finch", "flamingo", "fox", "gecko", "giraffe", "hedgehog", "heron",
            "ibis", "iguana", "jaguar", "kingfisher", "koala", "lemur", "llama", "lynx",
            "magpie", "manatee", "marmot", "meerkat", "narwhal", "newt", "ocelot", "otter",
            "owl", "panda", "pangolin", "pelican", "penguin", "puffin", "quail", "quokka",
            "raccoon", "robin", "seal", "sparrow", "tortoise", "toucan", "wombat", "wren"
    };

    private TaskBranchNames() { }

    static String generate(Set<String> taken) {
        return generate(taken, ThreadLocalRandom.current());
    }

    static String generate(Set<String> taken, RandomGenerator random) {
        int combinations = MOODS.length * TEXTURES.length * ANIMALS.length;
        int index = random.nextInt(combinations);
        // Each word list has a power-of-two size, so an odd stride visits every combination.
        // Keep those sizes: collisions must not trap the search in a subset of the names.
        int stride = 2 * random.nextInt(combinations / 2) + 1;
        for (int tried = 0; tried < combinations; tried++) {
            String candidate = nameAt(index);
            if (!taken.contains(candidate)) {
                return candidate;
            }
            index = (index + stride) % combinations;
        }
        throw new IllegalStateException("All generated worktree branch names are in use.");
    }

    private static String nameAt(int index) {
        String animal = ANIMALS[index % ANIMALS.length];
        index /= ANIMALS.length;
        return MOODS[index / TEXTURES.length] + "-" + TEXTURES[index % TEXTURES.length] + "-" + animal;
    }
}
