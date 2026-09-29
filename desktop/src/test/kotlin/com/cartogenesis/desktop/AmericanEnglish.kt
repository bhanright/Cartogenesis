package com.cartogenesis.desktop

/**
 * The spellings the project's readers are not shown: common British forms, as whole words. The
 * page, its roadmap and the application are written in American English (the maintainer's choice
 * of 2026-09-26), held by `SiteSourcesTest` over the page and by `AppSpellingTest` over the
 * application's own text.
 *
 * Whole words only, so an identifier such as `metresPerGreyLevel` is not read as prose: a name in
 * code is not something a reader meets, and renaming a wire name is a format change, not a
 * spelling fix (docs/CONVENTIONS.md, rule 11).
 */
internal object AmericanEnglish {

    val BRITISH_SPELLINGS = Regex(
        """\b(colou(?:rs?|red|ring|rful|rless)|grey(?:s|er|ish|scale)?|centre[ds]?|""" +
            """(?:kilo|centi|milli)?metres?|licence[sd]?|organis(?:e|es|ed|ing|ation)|analys(?:e|ed|ing)|""" +
            """(?:recogni|reali|customi|optimi|visuali|prioriti|minimi|maximi|generali|normali|emphasi|summari|""" +
            """finali|initiali|locali|randomi|standardi|synchroni|utili|categori|characteri|symboli|speciali|""" +
            """stabili|capitali|authori|memori|rasteri|seriali|paralleli)s(?:e|es|ed|ing|ation)|labell(?:ed|ing)|""" +
            """travell(?:ed|ing|er)|modell(?:ed|ing)|cancell(?:ed|ing)|favour(?:s|ed|ite|able)?|behaviours?|""" +
            """neighbour(?:s|ing|hood)?|harbours?|honours?|catalogues?|analogue|programmes?|defence|whilst|""" +
            """artefacts?|ploughs?|moulds?|sulphur|judgement)\b""",
        RegexOption.IGNORE_CASE
    )
}
