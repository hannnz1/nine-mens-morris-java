[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$WhitePlayer = "Alice",
    [string]$BlackPlayer = "Bob",
    [switch]$StartDocker
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
$ProjectRoot = Split-Path -Parent $PSScriptRoot

function Write-DemoStep {
    param([string]$Message)

    Write-Host ""
    Write-Host $Message -ForegroundColor Cyan
}

function Assert-Equal {
    param(
        [object]$Actual,
        [object]$Expected,
        [string]$Description
    )

    if ($Actual -ne $Expected) {
        throw "$Description. Expected '$Expected', received '$Actual'."
    }
}

function Invoke-JsonRequest {
    param(
        [ValidateSet("Get", "Post")]
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers,
        [object]$Body
    )

    $request = @{
        Method     = $Method
        Uri        = "$BaseUrl$Path"
        TimeoutSec = 15
    }
    if ($null -ne $Headers) {
        $request.Headers = $Headers
    }
    if ($null -ne $Body) {
        $jsonBody = $Body | ConvertTo-Json -Depth 10 -Compress
        $request.ContentType = "application/json"
        $request.Body = $jsonBody
        Write-Verbose "$Method $Path body: $jsonBody"
    }

    Invoke-RestMethod @request
}

function Wait-ForBackend {
    param([int]$Attempts)

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 3
            if ($health.status -eq "UP") {
                return
            }
        } catch {
            if ($attempt -eq $Attempts) {
                throw "The backend did not become healthy at $BaseUrl. Start it with 'docker compose up --build -d' or rerun this script with -StartDocker."
            }
        }
        Start-Sleep -Seconds 2
    }
}

function New-ActionBody {
    param(
        [string]$Type,
        [AllowNull()][object]$From,
        [string]$To,
        [long]$ExpectedVersion
    )

    @{
        type            = $Type
        from            = $From
        to              = $To
        expectedVersion = $ExpectedVersion
    }
}

function Invoke-GameAction {
    param(
        [string]$GameId,
        [string]$Token,
        [string]$IdempotencyKey,
        [hashtable]$Body
    )

    Invoke-JsonRequest `
        -Method Post `
        -Path "/api/v1/games/$GameId/actions" `
        -Headers @{
            "X-Player-Token" = $Token
            "Idempotency-Key" = $IdempotencyKey
        } `
        -Body $Body
}

function Read-ApiError {
    param([System.Management.Automation.ErrorRecord]$ErrorRecord)

    $responseText = $null
    if ($null -ne $ErrorRecord.ErrorDetails -and
        -not [string]::IsNullOrWhiteSpace($ErrorRecord.ErrorDetails.Message)) {
        $responseText = $ErrorRecord.ErrorDetails.Message
    }

    # Windows PowerShell 5.1 exposes an HTTP error body through the response
    # stream, while PowerShell 7 normally places it in ErrorDetails.Message.
    if ([string]::IsNullOrWhiteSpace($responseText) -and
        $null -ne $ErrorRecord.Exception.Response) {
        $response = $ErrorRecord.Exception.Response
        try {
            if ($response.PSObject.Methods.Name -contains "GetResponseStream") {
                $stream = $response.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    try {
                        $responseText = $reader.ReadToEnd()
                    } finally {
                        $reader.Dispose()
                    }
                }
            } elseif ($null -ne $response.Content) {
                $responseText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            }
        } catch {
            $responseText = $null
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($responseText)) {
        try {
            return $responseText | ConvertFrom-Json
        } catch {
            return $null
        }
    }
    return $null
}

if ($StartDocker) {
    Write-DemoStep "[1/9] Starting the backend and PostgreSQL with Docker Compose"
    Push-Location $ProjectRoot
    try {
        & docker compose up --build -d
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
    Wait-ForBackend -Attempts 60
} else {
    Write-DemoStep "[1/9] Checking backend health"
    Wait-ForBackend -Attempts 1
}
Write-Host "  Backend is healthy at $BaseUrl"

Write-DemoStep "[2/9] Alice creates a waiting game"
$created = Invoke-JsonRequest `
    -Method Post `
    -Path "/api/v1/games" `
    -Headers $null `
    -Body @{ whitePlayer = $WhitePlayer }

$gameId = [string]$created.game.id
$whiteToken = [string]$created.whiteCredential.token
Assert-Equal $created.game.status "WAITING_FOR_PLAYER" "A one-player game must wait for an opponent"
if ([string]::IsNullOrWhiteSpace($whiteToken)) {
    throw "The create response did not contain White's credential."
}
Write-Host "  Game: $gameId"
Write-Host "  Status: $($created.game.status)"
Write-Host "  White credential issued (not printed)"

Write-DemoStep "[3/9] Bob joins and receives the Black credential"
$joined = Invoke-JsonRequest `
    -Method Post `
    -Path "/api/v1/games/$gameId/join" `
    -Headers $null `
    -Body @{ blackPlayer = $BlackPlayer }

$blackToken = [string]$joined.credential.token
$version = [long]$joined.game.version
Assert-Equal $joined.game.status "IN_PROGRESS" "A joined game must be in progress"
Assert-Equal $joined.credential.side "BLACK" "The joining player must receive the Black side"
if ([string]::IsNullOrWhiteSpace($blackToken)) {
    throw "The join response did not contain Black's credential."
}
Write-Host "  Status: $($joined.game.status)"
Write-Host "  Current version: $version"
Write-Host "  Black credential issued (not printed)"

Write-DemoStep "[4/9] Players alternate placements"

$whiteA1Body = New-ActionBody -Type "PLACE" -From $null -To "A1" -ExpectedVersion $version
$whiteA1Key = "demo-$([guid]::NewGuid())"
$whiteA1 = Invoke-GameAction -GameId $gameId -Token $whiteToken -IdempotencyKey $whiteA1Key -Body $whiteA1Body
$version = [long]$whiteA1.version
Assert-Equal $whiteA1.state.board.A1 "WHITE" "White's A1 placement was not persisted"
Write-Host "  White PLACE A1 -> version $version"

$blackB2Body = New-ActionBody -Type "PLACE" -From $null -To "B2" -ExpectedVersion $version
$blackB2 = Invoke-GameAction -GameId $gameId -Token $blackToken -IdempotencyKey "demo-$([guid]::NewGuid())" -Body $blackB2Body
$version = [long]$blackB2.version
Write-Host "  Black PLACE B2 -> version $version"

$whiteD1Body = New-ActionBody -Type "PLACE" -From $null -To "D1" -ExpectedVersion $version
$whiteD1 = Invoke-GameAction -GameId $gameId -Token $whiteToken -IdempotencyKey "demo-$([guid]::NewGuid())" -Body $whiteD1Body
$version = [long]$whiteD1.version
Write-Host "  White PLACE D1 -> version $version"

$blackB4Body = New-ActionBody -Type "PLACE" -From $null -To "B4" -ExpectedVersion $version
$blackB4 = Invoke-GameAction -GameId $gameId -Token $blackToken -IdempotencyKey "demo-$([guid]::NewGuid())" -Body $blackB4Body
$version = [long]$blackB4.version
Write-Host "  Black PLACE B4 -> version $version"

Write-DemoStep "[5/9] White forms the A1-D1-G1 mill"
$whiteG1Body = New-ActionBody -Type "PLACE" -From $null -To "G1" -ExpectedVersion $version
$whiteG1 = Invoke-GameAction -GameId $gameId -Token $whiteToken -IdempotencyKey "demo-$([guid]::NewGuid())" -Body $whiteG1Body
$version = [long]$whiteG1.version
Assert-Equal $whiteG1.phase "REMOVE" "Forming a mill must enter the removal phase"
Assert-Equal $whiteG1.state.currentPlayer "WHITE" "White must retain the turn until removing a piece"
Write-Host "  White PLACE G1 -> phase $($whiteG1.phase), version $version"

Write-DemoStep "[6/9] White removes B2 and an identical retry remains idempotent"
$removeBody = New-ActionBody -Type "REMOVE" -From $null -To "B2" -ExpectedVersion $version
$removeKey = "demo-$([guid]::NewGuid())"
$removed = Invoke-GameAction -GameId $gameId -Token $whiteToken -IdempotencyKey $removeKey -Body $removeBody
$version = [long]$removed.version
Assert-Equal $removed.state.board.B2 "EMPTY" "B2 must be empty after removal"
Assert-Equal $removed.state.currentPlayer "BLACK" "The turn must pass to Black after removal"

$retried = Invoke-GameAction -GameId $gameId -Token $whiteToken -IdempotencyKey $removeKey -Body $removeBody
Assert-Equal ([long]$retried.version) $version "An idempotent retry must return the original response version"
Write-Host "  White REMOVE B2 -> version $version"
Write-Host "  Retry with the same key -> version $($retried.version) (no duplicate action)"

Write-DemoStep "[7/9] A stale Black request is rejected"
$staleBody = New-ActionBody `
    -Type "PLACE" `
    -From $null `
    -To "B6" `
    -ExpectedVersion ([long]$joined.game.version)

try {
    Invoke-GameAction `
        -GameId $gameId `
        -Token $blackToken `
        -IdempotencyKey "demo-$([guid]::NewGuid())" `
        -Body $staleBody | Out-Null
    throw "The stale request unexpectedly succeeded."
} catch {
    if ($_.Exception.Message -eq "The stale request unexpectedly succeeded.") {
        throw
    }
    $apiError = Read-ApiError $_
    if ($null -eq $apiError -or $apiError.code -ne "VERSION_CONFLICT") {
        throw "Expected VERSION_CONFLICT, but received: $($_.Exception.Message)"
    }
    Write-Host "  HTTP 409 $($apiError.code): $($apiError.message)"
}

Write-DemoStep "[8/9] Black refreshes the version and places B6 successfully"
$blackB6Body = New-ActionBody -Type "PLACE" -From $null -To "B6" -ExpectedVersion $version
$blackB6 = Invoke-GameAction -GameId $gameId -Token $blackToken -IdempotencyKey "demo-$([guid]::NewGuid())" -Body $blackB6Body
$version = [long]$blackB6.version
Assert-Equal $blackB6.state.board.B6 "BLACK" "Black's B6 placement was not persisted"
Assert-Equal $blackB6.state.currentPlayer "WHITE" "The turn must pass back to White"
Write-Host "  Black PLACE B6 -> version $version"

Write-DemoStep "[9/9] Reading and verifying the public final state"
$final = Invoke-JsonRequest -Method Get -Path "/api/v1/games/$gameId" -Headers $null -Body $null
Assert-Equal ([long]$final.version) $version "The public state must expose the latest version"
Assert-Equal $final.state.board.A1 "WHITE" "A1 must contain White"
Assert-Equal $final.state.board.D1 "WHITE" "D1 must contain White"
Assert-Equal $final.state.board.G1 "WHITE" "G1 must contain White"
Assert-Equal $final.state.board.B2 "EMPTY" "B2 must remain empty"
Assert-Equal $final.state.board.B4 "BLACK" "B4 must contain Black"
Assert-Equal $final.state.board.B6 "BLACK" "B6 must contain Black"

Write-Host ""
Write-Host "Multiplayer demo completed successfully." -ForegroundColor Green
Write-Host "  Game ID: $gameId"
Write-Host "  Version: $($final.version)"
Write-Host "  Phase: $($final.phase)"
Write-Host "  Current player: $($final.state.currentPlayer)"
Write-Host "  Board: White=A1,D1,G1; Black=B4,B6; B2 was removed"
Write-Host ""
Write-Host "The containers remain running for inspection. Stop them with: docker compose down"
