Для чтения UTF-8 файлов проекта предпочитай read_file. В PowerShell указывай Get-Content -Encoding UTF8; перед выводом кириллицы задай [Console]::OutputEncoding=[System.Text.Encoding]::UTF8.
