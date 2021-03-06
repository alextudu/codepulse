﻿Set-PSDebug -Strict
$ErrorActionPreference = 'Stop'

Push-Location $PSScriptRoot

. ..\Scripts\common.ps1

$heatPath = 'C:\Program Files (x86)\WiX Toolset v3.11\bin\heat.exe' 

function Write-Fragment($folderPath, $sourceFolderVariableName, $sourceFolderVariableValue, $outputFile, $componentGroupName, [switch] $isInRootDir) {
    & $heatPath dir $folderPath -o $outputFile -var "var.$sourceFolderVariableName" -sreg -cg $componentGroupName -dr INSTALLFOLDER -ke -ag

    $rootDirectoryId = $null
    if ($isInRootDir) {
        $data = [xml](gc $outputFile)
        $rootDirectoryId = $data.Wix.Fragment[0].DirectoryRef.Directory.Attributes[0].'#text'

        $data.Wix.RemoveChild($data.Wix.Fragment[0]) | out-null
        $data.Save($outputFile)
    }

    if ($rootDirectoryId -ne $null) {
        $lines = gc  $outputFile
        $lines = $lines | % { $_ -replace $rootDirectoryId,'INSTALLFOLDER' }
        Set-TextContent $outputFile $lines
    }

    Set-TextAt $outputFile 2 "  <?define $sourceFolderVariableName = `"$sourceFolderVariableValue`" ?>"
}

dir CodePulse.Installer.Win64\Win64* | % { remove-item $_ }

$win64CodePulseWxsPath = join-path $PSScriptRoot 'CodePulse.Installer.Win64\Win64CodePulse.wxs'
$win64SymbolServiceWxsPath = join-path $PSScriptRoot 'CodePulse.Installer.Win64\Win64SymbolService.wxs'
$win64TracersWxsPath = join-path $PSScriptRoot 'CodePulse.Installer.Win64\Win64Tracers.wxs'

Write-Fragment '.\Files\Win64\codepulse' 'CodePulseWin64SourceFolder' '..\Files\Win64\codepulse' $win64CodePulseWxsPath 'CodePulseApp' -isInRootDir
Write-Fragment '.\Files\Win64\dotnet-symbol-service' 'SymbolServiceWin64SourceFolder' '..\Files\Win64\dotnet-symbol-service' $win64SymbolServiceWxsPath 'SymbolService'
Write-Fragment '.\Files\Win64\tracers' 'TracersWin64SourceFolder' '..\Files\Win64\tracers' $win64TracersWxsPath 'Tracers'

Pop-Location
