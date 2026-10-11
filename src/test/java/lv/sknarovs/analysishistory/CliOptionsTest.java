package lv.sknarovs.analysishistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliOptionsTest {
    @Test void defaultsToCurrentFolderAndOutputInsideIt() throws Exception {
        assertEquals(new CliOptions(Path.of("."), Path.of("./analysis-history.xlsx"), false), CliOptions.parse());
    }

    @Test void outputFollowsDirUnlessGiven() throws Exception {
        assertEquals(new CliOptions(Path.of("res"), Path.of("res/analysis-history.xlsx"), false),
                CliOptions.parse("res"));
        CliOptions explicit = new CliOptions(Path.of("res"), Path.of("x.xlsx"), false);
        assertEquals(explicit, CliOptions.parse("-o", "x.xlsx", "res"));
        assertEquals(explicit, CliOptions.parse("res", "--output", "x.xlsx"));
        assertEquals(explicit, CliOptions.parse("-ox.xlsx", "res"));
        assertEquals(new CliOptions(Path.of("."), Path.of("x.xlsx"), false), CliOptions.parse("--output=x.xlsx"));
    }

    @Test void recognisesHelp() throws Exception {
        assertTrue(CliOptions.parse("-h").help());
        assertTrue(CliOptions.parse("--help").help());
    }

    @Test void rejectsSecondDirAndUnknownOption() {
        for (String[] args : List.of(new String[] {"a", "b"}, new String[] {"--nope"}, new String[] {"-x"},
                new String[] {"-o"}, new String[] {"-o", "--help"})) {
            assertThrows(CliOptions.UsageException.class, () -> CliOptions.parse(args), String.join(" ", args));
        }
    }
}
