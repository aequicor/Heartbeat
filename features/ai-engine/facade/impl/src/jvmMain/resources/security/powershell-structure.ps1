$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)

# This program parses data. It must never invoke, dot-source, expand or evaluate the submitted command.
function Literal($node) {
    if ($node -is [System.Management.Automation.Language.StringConstantExpressionAst]) { return $node.Value }
    if ($node -is [System.Management.Automation.Language.ExpandableStringExpressionAst] -and
        $node.NestedExpressions.Count -eq 0) { return $node.Value }
    if ($node -is [System.Management.Automation.Language.ConstantExpressionAst] -and
        $node.Value -is [int]) { return $node.Value.ToString([System.Globalization.CultureInfo]::InvariantCulture) }
    throw 'Nonliteral expression'
}

try {
    $source = [Console]::In.ReadToEnd()
    if ($source.Length -eq 0 -or $source.Length -gt 32768) { throw 'Input bound' }
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
    @{ statements = @($statements) } | ConvertTo-Json -Compress -Depth 12
} catch {
    # Errors may embed submitted input. Return a constant diagnostic only.
    [Console]::Out.Write('{"unknown":true}')
    exit 2
}
