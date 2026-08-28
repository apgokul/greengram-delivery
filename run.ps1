Get-Content .env | Where-Object { $_ -match '^\s*[^#]' -and $_ -match '=' } | ForEach-Object {
    $name, $value = $_.Split('=', 2)
    $name = $name.Trim()
    $value = $value.Trim()
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
mvn spring-boot:run
