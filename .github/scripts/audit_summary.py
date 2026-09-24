"""The nightly audit tier's summary: which cases failed, and which classes never ran.

Reads the plan `.github/audit-tier.init.gradle.kts` wrote before any test started, one line per
entry of each `audit` task's filter (a class, or one case of a class), and the JUnit XML the run
left behind. A planned class with no result file is reported as not run and fails this step.

That is what a test worker killed part-way through the tier leaves: Gradle writes no file for the
classes after the one it died in, and writes that one's finished cases with the case that was
running marked skipped, with no reason, rather than failed. Counting only the files present, as
this summary once did, reads that as green. So a skip with no reason is red, and because the class
the worker died in looks finished, how each tier's Gradle step ended is read as well: a failed step
whose results show nothing wrong is reported as a failure the results do not explain, never as
green.

The nightly runs each module's tier as a job of its own and gathers their results here. Each job
writes how its Gradle step ended to `build/audit-outcome-<module>.txt` beside its results, and the
summary job passes GitHub's own verdict on every job in AUDIT_JOB_RESULTS, as `name=result` pairs
separated by commas. A module the plan names with no outcome file left no word of how its tier
ended — a job cancelled, timed out or lost with its runner — and is red; so is any job whose result
is not `success`. Run by hand after a local `gradlew audit`, with neither of those present, the
outcome of the one Gradle run is read from AUDIT_TIER_OUTCOME when it is given.

Usage: audit_summary.py [ROOT]. ROOT is the repository root and defaults to the working directory.
Markdown goes to standard output and, when GitHub names one, to the step summary as well. Exits 1
when a case failed, a case was skipped with no reason, a planned class wrote no result, a result
file cannot be read, there is no plan at all, a tier's Gradle step did not succeed or left no word
of how it ended, or a job ended in anything but success; 0 only when none of those holds.
"""

import fnmatch
import glob
import os
import sys
import xml.etree.ElementTree as ET

PLAN = os.path.join("build", "audit-tier-plan.tsv")

#: Where a tier's job writes how its Gradle step ended: `audit-outcome-<module>.txt`.
OUTCOME_PREFIX = "audit-outcome-"


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


def sentence(clause):
    """`clause` with its first letter capitalised, to open a line of the summary."""
    return clause[:1].upper() + clause[1:]


def module_of(task):
    """The module a task path belongs to: `worldgen` for `:worldgen:audit`."""
    return task.strip(":").split(":")[0]


def read_outcomes(root):
    """How each module's tier ended, by module, from the files its job left; empty if none.

    Looked for under `build/` and, in case an upload's common root came out one level lower, at the
    root as well: a job whose tier wrote no results uploads its outcome file alone.
    """
    outcomes = {}
    for directory in (os.path.join(root, "build"), root):
        for path in sorted(glob.glob(os.path.join(directory, OUTCOME_PREFIX + "*.txt"))):
            module = os.path.basename(path)[len(OUTCOME_PREFIX):-len(".txt")]
            with open(path, encoding="utf-8") as outcome:
                outcomes.setdefault(module, outcome.read().strip() or "(empty)")
    return outcomes


def parse_job_results(text):
    """`name=result` pairs separated by commas, as the workflow passes them; {} for None or empty."""
    results = {}
    for pair in (text or "").split(","):
        pair = pair.strip()
        if not pair:
            continue
        name, _, result = pair.partition("=")
        results[name.strip()] = result.strip() or "(none)"
    return results


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


def summarise(root, gradle_outcome=None, job_results=None, outcomes=None):
    """The summary's lines and whether the tier is green.

    `gradle_outcome` is GitHub's outcome for a single step that ran the whole tier (`success`,
    `failure`, `cancelled` or `skipped`), or None when nobody named it. `job_results` maps each job
    of a split run to GitHub's result for it, and `outcomes` each module to how its tier's Gradle
    step ended, as its job recorded it; both None (or empty) when the tier was one run.
    """
    job_results = job_results or {}
    outcomes = outcomes if outcomes is not None else {}
    split = bool(job_results) or bool(outcomes)

    failed_jobs = ["the %s job ended `%s`" % (name, result)
                   for name, result in sorted(job_results.items()) if result != "success"]

    planned = read_plan(root)
    if planned is None:
        lines = ["## Audit tier",
                 "No plan was written: Gradle stopped before it chose which tests to run, so "
                 "nothing in the tier can be held to one."]
        lines += ["**%s.**" % sentence(job) for job in failed_jobs]
        return lines, False
    if not planned:
        return ["## Audit tier", "The plan names no classes, so nothing in the tier ran."], False

    suites_by_directory, failed, skipped, unexplained, unreadable = {}, [], [], [], []
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
                    # running is written as a skip with none, so that one is red.
                    reason = case.find("skipped").get("message")
                    skipped.append(qualified if reason else qualified + " (no reason given)")
                    if not reason:
                        unexplained.append(qualified)

    not_run = ["%s (%s)" % (entry, task)
               for task, results_directory, entry in planned
               if not entry_ran(entry, suites_by_directory[results_directory])]

    # How each module's tier ended. A split run must have left a word from every module the plan
    # names; a single run is read from the one outcome it was handed.
    tier_failures = []
    if split:
        for module in sorted({module_of(task) for task, _, _ in planned}):
            outcome = outcomes.get(module)
            if outcome is None:
                tier_failures.append("the %s tier left no word of how it ended" % module)
            elif outcome != "success":
                tier_failures.append("the %s tier's Gradle step ended `%s`" % (module, outcome))
    elif gradle_outcome is not None and gradle_outcome != "success":
        tier_failures.append("the Gradle step ended `%s`" % gradle_outcome)

    wrote = sum(len(suites) for suites in suites_by_directory.values())
    lines = ["## Audit tier"]
    if failed:
        lines.append("**%d of %d cases red:** %s" % (len(failed), cases, "; ".join(failed)))
    if not_run:
        lines.append("**%d of %d planned classes or cases wrote no result**, so they did not run "
                     "or did not finish: %s" % (len(not_run), len(planned), "; ".join(not_run)))
    if unexplained:
        lines.append("**%d cases were skipped with no reason**, which is how the case a dying "
                     "worker was running is written: %s" % (len(unexplained), "; ".join(unexplained)))
    if unreadable:
        lines.append("**Result files that could not be read (%d):** %s"
                     % (len(unreadable), "; ".join(unreadable)))
    results_green = not failed and not not_run and not unexplained and not unreadable
    for failure in tier_failures:
        if results_green:
            lines.append("**%s, although no case failed and every planned class wrote a result.** "
                         "A test worker that dies in a tier's last class leaves exactly this; so can "
                         "a failure outside the tests. The job's log says which." % sentence(failure))
        else:
            lines.append("**%s.**" % sentence(failure))
    for job in failed_jobs:
        lines.append("**%s.**" % sentence(job))
    if skipped:
        # Listed by name because the case a dying worker was running is written as a skip, and a
        # count alone would hide it among the ones that stood aside on purpose.
        lines.append("Skipped (%d): %s" % (len(skipped), "; ".join(skipped)))
    green = results_green and not tier_failures and not failed_jobs
    if green:
        lines.append("All %d cases green in %d classes, %d of them skipped with a reason; every one "
                     "of the %d planned classes or cases wrote a result."
                     % (cases, wrote, len(skipped), len(planned)))
    else:
        lines.append("%d cases read in %d result files, %d of them skipped."
                     % (cases, wrote, len(skipped)))
    return lines, green


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    lines, green = summarise(
        root,
        gradle_outcome=os.environ.get("AUDIT_TIER_OUTCOME") or None,
        job_results=parse_job_results(os.environ.get("AUDIT_JOB_RESULTS")),
        outcomes=read_outcomes(root),
    )
    text = "\n\n".join(lines) + "\n"
    sys.stdout.write(text)
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as summary:
            summary.write(text)
    return 0 if green else 1


if __name__ == "__main__":
    sys.exit(main())
