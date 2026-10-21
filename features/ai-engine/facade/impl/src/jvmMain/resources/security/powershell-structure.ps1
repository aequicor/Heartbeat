$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$PSModuleAutoLoadingPreference = 'None'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)

# This program parses data. It must never invoke, dot-source, expand or evaluate the submitted command.
function Literal($node) {
    if ($node -is [System.Management.Automation.Language.StringConstantExpressionAst]) { return $node.Value }
    if ($node -is [System.Management.Automation.Language.ExpandableStringExpressionAst] -and
        $node.NestedExpressions.Count -eq 0) { return $node.Value }
    if ($node -is [System.Management.Automation.Language.ConstantExpressionAst] -and
        $node.Value -is [int]) { return $node.Value.ToString([System.Globalization.CultureInfo]::InvariantCulture) }
    throw 'Nonliteral expression'
}

# Serialize only the fixed AST document types without importing PowerShell utility modules.
# Module discovery can emit progress or initialize per-user caches before the document is returned.
function Json($value) {
    if ($value -is [string]) {
        $text = [System.Text.StringBuilder]::new()
        [void]$text.Append('"')
        foreach ($character in $value.ToCharArray()) {
            if ($character -eq '"') { [void]$text.Append('\"') }
            elseif ($character -eq '\') { [void]$text.Append('\\') }
            elseif ([int]$character -lt 32) { [void]$text.Append(('\u{0:x4}' -f [int]$character)) }
            else { [void]$text.Append($character) }
        }
        [void]$text.Append('"')
        return $text.ToString()
    }
    if ($value -is [int]) { return $value.ToString([System.Globalization.CultureInfo]::InvariantCulture) }
    if ($value -is [System.Collections.IDictionary]) {
        $members = @()
        foreach ($key in $value.Keys) { $members += (Json ([string]$key)) + ':' + (Json $value[$key]) }
        return '{' + ($members -join ',') + '}'
    }
    if ($value -is [System.Collections.IEnumerable]) {
        $items = @()
        foreach ($item in $value) { $items += Json $item }
        return '[' + ($items -join ',') + ']'
    }
    throw 'Unsupported document value'
}

try {
    # Host-owned UTF-16 character count followed by UTF-8 text. Do not wait for EOF: another inherited pipe
    # handle must not delay parsing an already complete request. Kotlin String.length uses the same units.
    $sourceLength = 0
    if (-not [int]::TryParse([Console]::In.ReadLine(), [ref]$sourceLength) -or
        $sourceLength -lt 1 -or $sourceLength -gt 32768) { throw 'Input bound' }
    $sourceBuffer = [char[]]::new($sourceLength)
    $received = 0
    while ($received -lt $sourceLength) {
        $read = [Console]::In.Read($sourceBuffer, $received, $sourceLength - $received)
        if ($read -le 0) { throw 'Incomplete input' }
        $received += $read
    }
    $source = -join $sourceBuffer
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$errors)
    if ($errors.Count -ne 0 -or $null -ne $ast.ScriptRequirements -or $ast.UsingStatements.Count -ne 0 -or
        $null -ne $ast.ParamBlock -or $null -ne $ast.BeginBlock -or $null -ne $ast.CleanBlock -or
        $null -ne $ast.ProcessBlock -or $null -ne $ast.DynamicParamBlock -or $ast.EndBlock.Traps.Count -ne 0) {
        throw 'Unsupported script structure'
    }
    $statements = @()
    foreach ($statement in $ast.EndBlock.Statements) {
        if ($statement -isnot [System.Management.Automation.Language.PipelineAst] -or $statement.Background) {
            throw 'Unsupported statement'
        }
        $commands = @()
        foreach ($command in $statement.PipelineElements) {
            if ($command -isnot [System.Management.Automation.Language.CommandAst] -or
                $command.InvocationOperator -ne [System.Management.Automation.Language.TokenKind]::Unknown) {
                throw 'Unsupported invocation'
            }
            $words = @()
            foreach ($element in $command.CommandElements) {
                if ($element -is [System.Management.Automation.Language.CommandParameterAst]) {
                    if ($null -ne $element.Argument) { throw 'Colon parameter argument' }
                    $words += '-' + $element.ParameterName
                } else {
                    $literal = Literal $element
                    # A quoted '-Directory' is a positional path, not a parameter. Do not erase that distinction.
                    if ($literal.StartsWith('-')) { throw 'Parameter-like literal is unsupported' }
                    $words += $literal
                }
            }
            if ($words.Count -eq 0) { throw 'Empty command' }
            $redirects = @()
            foreach ($redirect in $command.Redirections) {
                if ($redirect -isnot [System.Management.Automation.Language.FileRedirectionAst]) {
                    throw 'Unsupported stream redirection'
                }
                $redirects += @{
                    target = (Literal $redirect.Location)
                    kind = $(if ($redirect.Append) { 'Append' } else { 'Output' })
                    fileDescriptor = [int]$redirect.FromStream
                }
            }
            $commands += @{ words = @($words); redirects = @($redirects) }
        }
        $statements += @{ commands = @($commands) }
    }
    if ($statements.Count -eq 0) { throw 'No statements' }
    [Console]::Out.Write((Json @{ statements = @($statements) }))
} catch {
    # Errors may embed submitted input. Return a constant diagnostic only.
    [Console]::Out.Write('{"unknown":true}')
    exit 2
}
