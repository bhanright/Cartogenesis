"""The nightly audit tier's summary: which cases failed, and which classes never ran.

Reads the plan `.github/audit-tier.init.gradle.kts` wrote before any test started, one line per
entry of each `audit` task's filter (a class, or one case of a class), and the JUnit XML the run
left behind. A planned class with no result file is reported as not run and fails this step.

That is what a test worker killed part-way through the tier leaves: Gradle writes no file for the
classes after the one it died in, and writes that one's finished cases with the case that was
running marked skipped rather than failed. Counting only the files present, as this summary once
did, reads that as green. Because the class the worker died in looks finished, the outcome of the
Gradle step is read as well, from AUDIT_TIER_OUTCOME: a failed run whose results show nothing wrong
is reported as a failure the results do not explain, never as green.

Usage: audit_summary.py [ROOT]. ROOT is the repository root and defaults to the working directory.
Markdown goes to standard output and, when GitHub names one, to the step summary as well. Exits 1
when a case failed, a planned class wrote no result, a result file cannot be read, there is no plan
at all, or the Gradle step failed; 0 only when every planned class wrote a result, none of its cases
failed and the Gradle step, when named, succeeded.
"""

import fnmatch
import glob
import os
import sys
import xml.etree.ElementTree as ET

PLAN = os.path.join("build", "audit-tier-plan.tsv")


def read_plan(root):
    """(task path, results directory, filter entry) for each line of the plan; None if absent."""
    path = os.path.join(root, PLAN)
    if not os.path.isfile(path):
        return None
    planned = []
    with open(path, encoding="utf-8") as plan:
        for line in plan:
            line = line.rstrip("\n")
            if line:
                task, results_directory, entry = line.split("\t")
                planned.append((task, results_directory, entry))
    return planned


def entry_ran(entry, suites):
    """Whether the run left a result for what the filter entry `entry` selects.

    `suites` maps each class that wrote a result file to the names of the cases in it. The entry is
    read the way `includeTestsMatching` reads it: a class name selects that class and the classes
    nested in it, `*` stands for any run of characters, and a class name followed by a method name
    selects that one case, which must then be among the class's results.
    """
    for suite, case_names in suites.items():
        if suite == entry or suite.startswith(entry + "$"):
            return True
        if "*" in entry and fnmatch.fnmatchcase(suite, entry):
            return True
        if entry.startswith(suite + "."):
            method = entry[len(suite) + 1:]
            # JUnit 5 writes a case's name with its parameter list, JUnit 4 without it.
            if any(name == method or name.startswith(method + "(") for name in case_names):
                return True
    return False


def summarise(root, gradle_outcome=None):
    """The summary's lines and whether the tier is green.

    `gradle_outcome` is GitHub's outcome for the step that ran the tier (`success`, `failure`,
    `cancelled` or `skipped`), or None when nobody named it.
    """
    planned = read_plan(root)
    if planned is None:
        return ["## Audit tier",
                "No plan was written: Gradle stopped before it chose which tests to run, so "
                "nothing in the tier ran."], False
    if not planned:
        return ["## Audit tier", "The plan names no classes, so nothing in the tier ran."], False

    suites_by_directory, failed, skipped, unreadable = {}, [], [], []
    cases = 0
    for results_directory in sorted({directory for _, directory, _ in planned}):
        suites = suites_by_directory.setdefault(results_directory, {})
        for path in sorted(glob.glob(os.path.join(root, results_directory, "*.xml"))):
            try:
                suite = ET.parse(path).getroot()
            except ET.ParseError as error:
                # A file cut off mid-write is a class that did not finish, not one that passed.
                unreadable.append("%s (%s)" % (os.path.relpath(path, root), error))
                continue
            case_names = suites.setdefault(suite.get("name", "?"), set())
            for case in suite.iter("testcase"):
                cases += 1
                case_names.add(case.get("name", "?"))
                qualified = "%s.%s" % (case.get("classname", "?"), case.get("name", "?"))
                if case.find("failure") is not None or case.find("error") is not None:
                    failed.append(qualified)
                elif case.find("skipped") is not None:
                    # A skip the test asked for carries its reason; the case a dying worker was
                    # running is written as a skip with none, so that one is marked.
                    reason = case.find("skipped").get("message")
                    skipped.append(qualified if reason else qualified + " (no reason given)")

    not_run = ["%s (%s)" % (entry, task)
               for task, results_directory, entry in planned
               if not entry_ran(entry, suites_by_directory[results_directory])]

    wrote = sum(len(suites) for suites in suites_by_directory.values())
    lines = ["## Audit tier"]
    if failed:
        lines.append("**%d of %d cases red:** %s" % (len(failed), cases, "; ".join(failed)))
    if not_run:
        lines.append("**%d of %d planned classes or cases wrote no result**, so they did not run "
                     "or did not finish: %s" % (len(not_run), len(planned), "; ".join(not_run)))
    if unreadable:
        lines.append("**Result files that could not be read (%d):** %s"
                     % (len(unreadable), "; ".join(unreadable)))
    results_green = not failed and not not_run and not unreadable
    gradle_failed = gradle_outcome is not None and gradle_outcome != "success"
    if results_green and gradle_failed:
        lines.append("**The Gradle step ended `%s` although no case failed and every planned class "
                     "wrote a result.** A test worker that dies in the tier's last class leaves "
                     "exactly this, with the case it was running marked skipped below; so can a "
                     "failure outside the tests. The step's log says which." % gradle_outcome)
    if skipped:
        # Listed by name because the case a dying worker was running is written as a skip, and a
        # count alone would hide it among the ones that stood aside on purpose.
        lines.append("Skipped (%d): %s" % (len(skipped), "; ".join(skipped)))
    green = results_green and not gradle_failed
    if green:
        lines.append("All %d cases green in %d classes, %d of them skipped; every one of the %d "
                     "planned classes or cases wrote a result."
                     % (cases, wrote, len(skipped), len(planned)))
    else:
        lines.append("%d cases read in %d result files, %d of them skipped."
                     % (cases, wrote, len(skipped)))
    return lines, green


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    lines, green = summarise(root, os.environ.get("AUDIT_TIER_OUTCOME") or None)
    text = "\n\n".join(lines) + "\n"
    sys.stdout.write(text)
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as summary:
            summary.write(text)
    return 0 if green else 1


if __name__ == "__main__":
    sys.exit(main())
