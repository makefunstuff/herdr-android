package com.makefunstuff.herdr.core.code

/**
 * Syntax highlighting for the code viewer, line by line: a small tokenizer per kind of language
 * (C-like code, markup, data files, Markdown, diffs) with each language's words. It marks the
 * herdr web UI's roles (`--syntax-keyword`, `--syntax-string`…); the app colours them.
 *
 * A line is read with the state the line before it ended in (inside a block comment, a
 * triple-quoted string, a template, a fenced block), so a file is highlighted from any line once
 * [Syntax.states] has run over it.
 */
enum class Role { Keyword, Str, Number, Comment, Function, Type, Variable, Meta, Inserted, Deleted }

data class Span(val start: Int, val end: Int, val role: Role)

data class Highlighted(val spans: List<Span>, val next: Int)

class Language(
    val name: String,
    val kind: Kind = Kind.Code,
    keywords: String = "",
    literals: String = "true false null",
    types: String = "",
    val lineComments: List<String> = listOf("//"),
    val block: Pair<String, String>? = "/*" to "*/",
    val quotes: String = "\"'",
    val triple: List<String> = emptyList(),
    /** Backtick strings that may span lines (JavaScript templates, Go raw strings). */
    val template: Boolean = false,
    /** `@Annotation` / `@decorator`. */
    val annotations: Boolean = false,
    /** A `#` line is a preprocessor line (C), or `#[attr]` is an attribute (Rust). */
    val hashMeta: Boolean = false,
    /** `$name` is a variable (shells, PHP, Perl). */
    val dollar: Boolean = false,
    /** A capitalised name is a type. */
    val capitalTypes: Boolean = true,
    val caseInsensitive: Boolean = false,
) {
    enum class Kind { Code, Markup, Data, Markdown, Diff }

    val keywords: Set<String> = words(keywords)
    val literals: Set<String> = words(literals)
    val types: Set<String> = words(types)

    private fun words(s: String) = s.split(' ', '\n').filter { it.isNotEmpty() }.map { if (caseInsensitive) it.lowercase() else it }.toSet()
}

object Syntax {
    /** Lines longer than this are left plain: a minified file would take long and show nothing useful. */
    const val LONGEST = 3000

    private const val NORMAL = 0
    private const val BLOCK = 1
    private const val TRIPLE = 2 // + index into Language.triple
    private const val TEMPLATE = 8
    private const val FENCE = 9

    private const val C_KEYWORDS = "auto break case char const continue default do double else enum extern float for goto if inline int long " +
        "register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while"
    private const val CPP_KEYWORDS = "$C_KEYWORDS alignas alignof and asm bool catch class concept consteval constexpr constinit co_await co_return " +
        "co_yield decltype delete explicit export final friend mutable namespace new noexcept not operator or override private protected " +
        "public requires static_assert template this thread_local throw try typeid typename using virtual"

    val kotlin = Language(
        "kotlin",
        keywords = "as break class continue do else for fun if in interface is object package return super this throw try typealias typeof val var when while " +
            "by catch constructor delegate dynamic field file finally get import init param property receiver set setparam where actual abstract annotation " +
            "companion const crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out override private " +
            "protected public reified sealed suspend tailrec vararg value",
        types = "Int Long Short Byte Float Double Boolean Char String Unit Any Nothing",
        triple = listOf("\"\"\""), annotations = true, dollar = false,
    )
    val java = Language(
        "java",
        keywords = "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto " +
            "if implements import instanceof int interface long native new package private protected public return short static strictfp super switch " +
            "synchronized this throw throws transient try void volatile while var record sealed permits yield",
        triple = listOf("\"\"\""), annotations = true,
    )
    val groovy = Language(
        "groovy",
        keywords = "as assert break case catch class def default do else enum extends final finally for if implements import in instanceof interface new " +
            "package return static super switch this throw throws trait try var void while",
        triple = listOf("\"\"\"", "'''"), annotations = true, dollar = true,
    )
    val javascript = Language(
        "javascript",
        keywords = "async await break case catch class const continue debugger default delete do else export extends finally for from function get if import " +
            "in instanceof let new of return set static super switch this throw try typeof var void while with yield",
        literals = "true false null undefined NaN Infinity", template = true, annotations = true,
    )
    val typescript = Language(
        "typescript",
        keywords = javascript.keywords.joinToString(" ") + " abstract as asserts declare enum implements infer interface is keyof module namespace never " +
            "override private protected public readonly satisfies type unique",
        literals = "true false null undefined NaN Infinity",
        types = "string number boolean any unknown void object bigint symbol",
        template = true, annotations = true,
    )
    val python = Language(
        "python",
        keywords = "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or " +
            "pass raise return try while with yield match case",
        literals = "True False None", types = "int float str bool bytes list dict set tuple object",
        lineComments = listOf("#"), block = null, triple = listOf("\"\"\"", "'''"), annotations = true,
    )
    val rust = Language(
        "rust",
        keywords = "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub ref return self Self " +
            "static struct super trait type unsafe use where while macro_rules",
        types = "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box",
        hashMeta = true,
    )
    val go = Language(
        "go",
        keywords = "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select " +
            "struct switch type var",
        literals = "true false nil iota",
        types = "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any",
        template = true, capitalTypes = false,
    )
    val c = Language("c", keywords = C_KEYWORDS, literals = "true false NULL", hashMeta = true, capitalTypes = false)
    val cpp = Language("cpp", keywords = CPP_KEYWORDS, literals = "true false nullptr NULL", hashMeta = true)
    val csharp = Language(
        "csharp",
        keywords = "abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum event " +
            "explicit extern finally fixed float for foreach goto if implicit in int interface internal is lock long namespace new object operator out " +
            "override params private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct switch this throw try " +
            "typeof uint ulong unchecked unsafe ushort using var virtual void volatile while async await record get set init yield",
        hashMeta = true,
    )
    val swift = Language(
        "swift",
        keywords = "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public " +
            "rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch " +
            "where while as catch is super self Self throw throws try async await some any",
        literals = "true false nil", triple = listOf("\"\"\""), annotations = true,
    )
    val dart = Language(
        "dart",
        keywords = "abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends " +
            "extension external factory final finally for get if implements import in interface is late library mixin new on operator part required " +
            "rethrow return set show static super switch sync this throw try typedef var void while with yield",
        triple = listOf("\"\"\"", "'''"), annotations = true,
    )
    val scala = Language(
        "scala",
        keywords = "abstract case catch class def do else extends final finally for forSome if implicit import lazy match new object override package " +
            "private protected return sealed super this throw trait try type val var while with yield given using enum then",
        triple = listOf("\"\"\""), annotations = true,
    )
    val php = Language(
        "php",
        keywords = "abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare " +
            "endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global goto if implements include instanceof " +
            "insteadof interface isset list match namespace new or print private protected public readonly require return static switch throw trait try " +
            "unset use var while xor yield",
        lineComments = listOf("//", "#"), dollar = true,
    )
    val ruby = Language(
        "ruby",
        keywords = "alias and begin break case class def defined? do else elsif end ensure for if in module next not or redo rescue retry return self " +
            "super then undef unless until when while yield require attr_accessor attr_reader private protected public",
        literals = "true false nil", lineComments = listOf("#"), block = "=begin" to "=end",
    )
    val lua = Language(
        "lua",
        keywords = "and break do else elseif end for function goto if in local not or repeat return then until while",
        literals = "true false nil", lineComments = listOf("--"), block = "--[[" to "]]", capitalTypes = false,
    )
    val shell = Language(
        "shell",
        keywords = "if then else elif fi case esac for select while until do done in function time return exit export local readonly declare set " +
            "unset shift source alias echo printf cd test eval exec trap",
        literals = "true false", lineComments = listOf("#"), block = null, dollar = true, capitalTypes = false, template = true,
    )
    val sql = Language(
        "sql",
        keywords = "select from where and or not insert into values update set delete create table index view drop alter add column primary key " +
            "foreign references join left right inner outer full on as group by order having limit offset union all distinct case when then else end " +
            "is in like between exists returning with default constraint unique check begin commit rollback transaction if",
        literals = "true false null", types = "int integer bigint smallint text varchar char boolean date timestamp timestamptz numeric real serial uuid jsonb json",
        lineComments = listOf("--"), caseInsensitive = true, capitalTypes = false,
    )
    val css = Language(
        "css",
        keywords = "@media @import @supports @keyframes @font-face @layer @container !important",
        literals = "", lineComments = emptyList(), quotes = "\"'", capitalTypes = false,
    )
    val zig = Language(
        "zig",
        keywords = "addrspace align allowzero and anyframe anytype asm async await break callconv catch comptime const continue defer else enum errdefer " +
            "error export extern fn for if inline linksection noalias noinline nosuspend opaque or orelse packed pub resume return struct suspend switch " +
            "test threadlocal try union unreachable usingnamespace var volatile while",
        literals = "true false null undefined",
    )
    val nix = Language(
        "nix",
        keywords = "let in with rec inherit if then else assert import",
        literals = "true false null", lineComments = listOf("#"), triple = listOf("''"),
    )
    val makefile = Language(
        "makefile", keywords = "ifeq ifneq ifdef ifndef else endif include define endef export override", literals = "",
        lineComments = listOf("#"), block = null, dollar = true, capitalTypes = false,
    )
    val dockerfile = Language(
        "dockerfile",
        keywords = "FROM RUN CMD LABEL MAINTAINER EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL AS",
        literals = "", lineComments = listOf("#"), block = null, dollar = true, capitalTypes = false,
    )

    val json = Language("json", Language.Kind.Data, lineComments = listOf("//"), quotes = "\"")
    val yaml = Language("yaml", Language.Kind.Data, literals = "true false null yes no on off ~", lineComments = listOf("#"), block = null)
    val toml = Language("toml", Language.Kind.Data, literals = "true false", lineComments = listOf("#", ";"), block = null, triple = listOf("\"\"\"", "'''"))
    private val ignore = Language("ignore", Language.Kind.Data, literals = "", lineComments = listOf("#"), block = null)
    val markup = Language("xml", Language.Kind.Markup, block = "<!--" to "-->")
    val markdown = Language("markdown", Language.Kind.Markdown)
    val diff = Language("diff", Language.Kind.Diff)

    private val byExtension: Map<String, Language> = buildMap {
        fun put(lang: Language, vararg ext: String) = ext.forEach { put(it, lang) }
        put(kotlin, "kt", "kts")
        put(java, "java")
        put(groovy, "groovy", "gradle")
        put(javascript, "js", "jsx", "mjs", "cjs")
        put(typescript, "ts", "tsx", "mts", "cts")
        put(python, "py", "pyi", "pyw")
        put(rust, "rs")
        put(go, "go")
        put(c, "c", "h")
        put(cpp, "cc", "cpp", "cxx", "hpp", "hh", "hxx", "ino", "m", "mm")
        put(csharp, "cs")
        put(swift, "swift")
        put(dart, "dart")
        put(scala, "scala", "sc")
        put(php, "php")
        put(ruby, "rb", "rake", "gemspec")
        put(lua, "lua")
        put(shell, "sh", "bash", "zsh", "fish", "ksh", "envrc")
        put(sql, "sql")
        put(css, "css", "scss", "sass", "less")
        put(zig, "zig")
        put(nix, "nix")
        put(json, "json", "jsonc", "json5", "jsonl", "ndjson", "lock", "webmanifest")
        put(yaml, "yaml", "yml")
        put(toml, "toml", "ini", "cfg", "conf", "properties", "editorconfig")
        put(markup, "xml", "html", "htm", "svg", "xhtml", "plist", "vue", "svelte", "xsd", "xsl")
        put(markdown, "md", "markdown", "mdx")
        put(diff, "diff", "patch")
    }

    private val byName: Map<String, Language> = mapOf(
        "makefile" to makefile, "gnumakefile" to makefile, "dockerfile" to dockerfile, "containerfile" to dockerfile,
        "jenkinsfile" to groovy, "gemfile" to ruby, "rakefile" to ruby, "podfile" to ruby, "cmakelists.txt" to makefile,
        ".bashrc" to shell, ".zshrc" to shell, ".profile" to shell, ".bash_profile" to shell, "pkgbuild" to shell,
        ".gitignore" to ignore, ".dockerignore" to ignore, ".gitattributes" to ignore,
    )

    /** The language of [path], or of its first line's `#!`; null for plain text. */
    fun language(path: String, firstLine: String? = null): Language? {
        val name = path.substringAfterLast('/').lowercase()
        byName[name]?.let { return it }
        if (name.startsWith("dockerfile")) return dockerfile
        if (name.endsWith(".gradle.kts")) return kotlin
        val ext = name.substringAfterLast('.', "")
        if (ext.isNotEmpty() && name != ".$ext") byExtension[ext]?.let { return it }
        if (name.startsWith(".env")) return shell
        val bang = firstLine?.takeIf { it.startsWith("#!") } ?: return null
        return when {
            "python" in bang -> python
            "node" in bang || "deno" in bang || "bun" in bang -> javascript
            "ruby" in bang -> ruby
            "perl" in bang -> shell
            "sh" in bang -> shell
            else -> null
        }
    }

    /** The state each line starts in, for highlighting any of them on its own. */
    fun states(lang: Language, lines: List<String>): IntArray {
        val out = IntArray(lines.size)
        var s = NORMAL
        for (i in lines.indices) {
            out[i] = s
            s = line(lang, lines[i], s).next
        }
        return out
    }

    fun line(lang: Language, text: String, state: Int = NORMAL): Highlighted {
        if (text.length > LONGEST) return Highlighted(emptyList(), state)
        return when (lang.kind) {
            Language.Kind.Code -> Tokens(lang, text).code(state)
            Language.Kind.Data -> data(lang, text, state)
            Language.Kind.Markup -> Tokens(lang, text).markup(state)
            Language.Kind.Markdown -> markdown(text, state)
            Language.Kind.Diff -> Highlighted(diffLine(text), NORMAL)
        }
    }

    private fun diffLine(text: String): List<Span> {
        val role = when {
            text.startsWith("+++") || text.startsWith("---") || text.startsWith("diff ") || text.startsWith("index ") -> Role.Comment
            text.startsWith("+") -> Role.Inserted
            text.startsWith("-") -> Role.Deleted
            text.startsWith("@@") -> Role.Meta
            else -> return emptyList()
        }
        return listOf(Span(0, text.length, role))
    }

    private val DATA_KEY = Regex("""^(\s*(?:-\s+)?)("[^"]*"|'[^']*'|[A-Za-z0-9_.\-/@$ ]*?[A-Za-z0-9_.\-/@$])(\s*)([:=])(\s|$)""")
    private val SECTION = Regex("""^\s*\[\[?[^\]]*\]\]?\s*$""")

    /** JSON, YAML, TOML and INI: keys as variables, `[sections]` as types, the values as code. */
    private fun data(lang: Language, text: String, state: Int): Highlighted {
        if (state != NORMAL) return Tokens(lang, text).code(state)
        if (lang.name != "json") {
            val trimmed = text.trimStart()
            if (lang.lineComments.any { trimmed.startsWith(it) }) return Highlighted(listOf(Span(0, text.length, Role.Comment)), NORMAL)
            if (SECTION.matches(text)) return Highlighted(listOf(Span(text.indexOf('['), text.lastIndexOf(']') + 1, Role.Type)), NORMAL)
            if (trimmed == "---" || trimmed == "...") return Highlighted(listOf(Span(0, text.length, Role.Meta)), NORMAL)
            val m = DATA_KEY.find(text)
            if (m != null && !(lang.name == "yaml" && m.groupValues[4] == "=")) {
                val keyStart = m.groups[2]!!.range.first
                val keyEnd = m.groups[2]!!.range.last + 1
                val rest = Tokens(lang, text).code(NORMAL, from = m.groups[4]!!.range.last + 1)
                return Highlighted(listOf(Span(keyStart, keyEnd, Role.Variable)) + rest.spans, rest.next)
            }
        }
        val h = Tokens(lang, text).code(state)
        if (lang.name != "json") return h
        // A JSON string followed by ':' is a key.
        val spans = h.spans.map { s ->
            if (s.role != Role.Str) return@map s
            var j = s.end
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && text[j] == ':') s.copy(role = Role.Variable) else s
        }
        return Highlighted(spans, h.next)
    }

    private val HEADING = Regex("""^ {0,3}#{1,6}(\s|$)""")
    private val LIST = Regex("""^\s*([-*+]|\d+[.)])\s""")
    private val INLINE_CODE = Regex("`[^`]+`")
    private val LINK = Regex("""\[[^\]]*\]\(([^)\s]+)[^)]*\)""")

    private fun markdown(text: String, state: Int): Highlighted {
        val fence = text.trimStart().startsWith("```") || text.trimStart().startsWith("~~~")
        if (state == FENCE) return Highlighted(listOf(Span(0, text.length, if (fence) Role.Meta else Role.Str)), if (fence) NORMAL else FENCE)
        if (fence) return Highlighted(listOf(Span(0, text.length, Role.Meta)), FENCE)
        if (HEADING.containsMatchIn(text)) return Highlighted(listOf(Span(0, text.length, Role.Keyword)), NORMAL)
        if (text.trimStart().startsWith(">")) return Highlighted(listOf(Span(0, text.length, Role.Comment)), NORMAL)
        val spans = ArrayList<Span>()
        LIST.find(text)?.let { spans += Span(it.groups[1]!!.range.first, it.groups[1]!!.range.last + 1, Role.Meta) }
        INLINE_CODE.findAll(text).forEach { spans += Span(it.range.first, it.range.last + 1, Role.Str) }
        LINK.findAll(text).forEach { m ->
            val u = m.groups[1]!!.range
            if (spans.none { it.start <= u.first && u.first < it.end }) spans += Span(u.first, u.last + 1, Role.Function)
        }
        return Highlighted(spans.sortedBy { it.start }, NORMAL)
    }

    /** One line's tokens. */
    private class Tokens(val lang: Language, val text: String) {
        val spans = ArrayList<Span>()
        val n = text.length

        fun add(start: Int, end: Int, role: Role) {
            if (end > start) spans += Span(start, minOf(end, n), role)
        }

        fun code(state: Int, from: Int = 0): Highlighted {
            var i = from
            // Carried over from the line before.
            when {
                state == BLOCK -> {
                    val end = lang.block?.let { text.indexOf(it.second) } ?: -1
                    if (end < 0) return done(0, n, Role.Comment, BLOCK)
                    add(0, end + lang.block!!.second.length, Role.Comment); i = end + lang.block.second.length
                }
                state in TRIPLE until TEMPLATE -> {
                    val q = lang.triple.getOrNull(state - TRIPLE) ?: return Highlighted(emptyList(), NORMAL)
                    val end = text.indexOf(q)
                    if (end < 0) return done(0, n, Role.Str, state)
                    add(0, end + q.length, Role.Str); i = end + q.length
                }
                state == TEMPLATE -> {
                    val end = closing(0, '`')
                    if (end < 0) return done(0, n, Role.Str, TEMPLATE)
                    add(0, end + 1, Role.Str); i = end + 1
                }
            }
            val firstWord = text.indexOfFirst { !it.isWhitespace() }
            while (i < n) {
                val ch = text[i]
                if (ch.isWhitespace()) { i++; continue }
                // Comments.
                val lc = lang.lineComments.firstOrNull { text.startsWith(it, i) }
                if (lc != null && (lc != "#" || i == 0 || text[i - 1].isWhitespace() || text[i - 1] in "(;{")) {
                    if (!(lang.block != null && text.startsWith(lang.block.first, i))) { add(i, n, Role.Comment); break }
                }
                val b = lang.block
                if (b != null && text.startsWith(b.first, i) && (b.first != "=begin" || i == 0)) {
                    val end = text.indexOf(b.second, i + b.first.length)
                    if (end < 0) return done(i, n, Role.Comment, BLOCK)
                    add(i, end + b.second.length, Role.Comment); i = end + b.second.length; continue
                }
                // Strings.
                val t = lang.triple.indexOfFirst { text.startsWith(it, i) }
                if (t >= 0) {
                    val q = lang.triple[t]
                    val end = text.indexOf(q, i + q.length)
                    if (end < 0) return done(i, n, Role.Str, TRIPLE + t)
                    add(i, end + q.length, Role.Str); i = end + q.length; continue
                }
                if (ch == '`' && lang.template) {
                    val end = closing(i + 1, '`')
                    if (end < 0) return done(i, n, Role.Str, TEMPLATE)
                    add(i, end + 1, Role.Str); i = end + 1; continue
                }
                if (ch in lang.quotes && !(ch == '\'' && lang.name == "rust" && !rustChar(i))) {
                    val end = closing(i + 1, ch)
                    val stop = if (end < 0) n else end + 1
                    add(i, stop, Role.Str); i = stop; continue
                }
                // Meta: annotations, preprocessor lines and attributes.
                if (ch == '@' && lang.annotations && i + 1 < n && text[i + 1].isLetter()) {
                    val end = word(i + 1, extra = ".")
                    add(i, end, Role.Meta); i = end; continue
                }
                if (ch == '#' && lang.hashMeta) {
                    if (i == firstWord && lang.name != "rust") { add(i, n, Role.Meta); break }
                    if (i + 1 < n && (text[i + 1] == '[' || text[i + 1] == '!')) {
                        val end = text.indexOf(']', i).let { if (it < 0) n else it + 1 }
                        add(i, end, Role.Meta); i = end; continue
                    }
                }
                if (ch == '$' && lang.dollar && i + 1 < n) {
                    val nx = text[i + 1]
                    val end = when {
                        nx == '{' || nx == '(' -> text.indexOf(if (nx == '{') '}' else ')', i).let { if (it < 0) n else it + 1 }
                        nx.isLetter() || nx == '_' -> word(i + 1)
                        nx.isDigit() || nx in "@#?*!$-" -> i + 2
                        else -> -1
                    }
                    if (end > 0) { add(i, end, Role.Variable); i = end; continue }
                }
                // Numbers.
                if (ch.isDigit() || (ch == '.' && i + 1 < n && text[i + 1].isDigit() && (i == 0 || !text[i - 1].isLetterOrDigit()))) {
                    if (i > 0 && (text[i - 1].isLetter() || text[i - 1] == '_')) { i++; continue }
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || (text[j] == '.' && j + 1 < n && text[j + 1].isDigit()))) j++
                    add(i, j, Role.Number); i = j; continue
                }
                // Words.
                if (ch.isLetter() || ch == '_' || (lang.name == "css" && ch == '-')) {
                    val end = word(i, extra = if (lang.name == "css") "-" else if (lang.name == "ruby") "?!" else "")
                    val w = text.substring(i, end)
                    val key = if (lang.caseInsensitive) w.lowercase() else w
                    var j = end
                    while (j < n && text[j] == ' ') j++
                    val role = when {
                        key in lang.keywords -> Role.Keyword
                        key in lang.literals -> Role.Number
                        key in lang.types -> Role.Type
                        lang.name == "css" && j < n && text[j] == ':' -> Role.Variable
                        j < n && text[j] == '(' -> Role.Function
                        lang.capitalTypes && w[0].isUpperCase() && w.any { it.isLowerCase() } -> Role.Type
                        else -> null
                    }
                    role?.let { add(i, end, it) }
                    i = end; continue
                }
                if (ch == '@' && lang.name == "css") {
                    val end = word(i + 1, extra = "-")
                    add(i, end, Role.Keyword); i = end; continue
                }
                i++
            }
            return Highlighted(spans, NORMAL)
        }

        /** XML and HTML: tags as keywords, attributes as variables, their values as strings, entities as meta. */
        fun markup(state: Int): Highlighted {
            var i = 0
            if (state == BLOCK) {
                val end = text.indexOf("-->")
                if (end < 0) return done(0, n, Role.Comment, BLOCK)
                add(0, end + 3, Role.Comment); i = end + 3
            }
            while (i < n) {
                val ch = text[i]
                when {
                    text.startsWith("<!--", i) -> {
                        val end = text.indexOf("-->", i + 4)
                        if (end < 0) return done(i, n, Role.Comment, BLOCK)
                        add(i, end + 3, Role.Comment); i = end + 3
                    }
                    ch == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] in "/!?") -> {
                        var j = i + 1
                        if (text[j] in "/!?") j++
                        val nameEnd = word(j, extra = "-:.")
                        add(i + 1, nameEnd, if (text[i + 1] in "!?") Role.Meta else Role.Keyword)
                        i = attributes(nameEnd)
                    }
                    ch == '&' -> {
                        val end = text.indexOf(';', i)
                        if (end in (i + 2)..(i + 10)) { add(i, end + 1, Role.Meta); i = end + 1 } else i++
                    }
                    else -> i++
                }
            }
            return Highlighted(spans, NORMAL)
        }

        private fun attributes(from: Int): Int {
            var i = from
            while (i < n && text[i] != '>') {
                val ch = text[i]
                when {
                    ch == '"' || ch == '\'' -> {
                        val end = text.indexOf(ch, i + 1).let { if (it < 0) n else it + 1 }
                        add(i, end, Role.Str); i = end
                    }
                    ch.isLetter() || ch == '_' || ch == ':' || ch == '@' -> {
                        val end = word(i, extra = "-:.@")
                        add(i, end, Role.Variable); i = end
                    }
                    else -> i++
                }
            }
            return if (i < n) i + 1 else n
        }

        private fun rustChar(i: Int): Boolean = (i + 2 < n && text[i + 2] == '\'') || (i + 1 < n && text[i + 1] == '\\')

        /** The index of the quote that closes a string, skipping escaped ones; -1 when it runs past the line. */
        private fun closing(from: Int, q: Char): Int {
            var j = from
            while (j < n) {
                when (text[j]) {
                    '\\' -> j += 2
                    q -> return j
                    else -> j++
                }
            }
            return -1
        }

        private fun word(from: Int, extra: String = ""): Int {
            var j = from
            while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] in extra)) j++
            return j
        }

        private fun done(start: Int, end: Int, role: Role, next: Int): Highlighted {
            add(start, end, role)
            return Highlighted(spans, next)
        }
    }
}
