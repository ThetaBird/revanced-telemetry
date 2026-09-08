"""Check local path argument handling using the actual bundled CLI parser."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from patch_apk import argument_file_line


class ArgumentFileTests(unittest.TestCase):
    def test_newlines_rejected(self):
        for value in ('value\n--another-option', 'value\r--another-option'):
            with self.assertRaises(ValueError):
                argument_file_line(value)

    def test_picocli_preserves_special_characters(self):
        jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
        cli = ROOT / '.local/toolchain/revanced-cli-6.0.0-all.jar'
        if not (jdk / 'bin/java').exists() or not cli.exists():
            self.skipTest('Run scripts/build.sh with a JDK first')
        values = ['/private/path/input.apk', 'path with spaces # " \\ $() `text`', "single'quote"]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            source = path / 'ReadArgs.java'
            source.write_text('''
import picocli.CommandLine;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
@CommandLine.Command(name="readargs")
class ReadArgs implements Runnable {
 @CommandLine.Parameters(arity="0..*") String[] values;
 public void run() {
  for (String value: values)
   System.out.println(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
 }
 public static void main(String[] args) { System.exit(new CommandLine(new ReadArgs()).execute(args)); }
}
''')
            argument_file = path / 'arguments'
            argument_file.write_text(''.join(argument_file_line(value) for value in values))
            result = subprocess.run([str(jdk / 'bin/java'), '-cp', str(cli), str(source), '@' + str(argument_file)],
                                    text=True, capture_output=True, check=True)
            import base64
            self.assertEqual(values, [base64.b64decode(line).decode() for line in result.stdout.splitlines()])


if __name__ == '__main__':
    unittest.main()
