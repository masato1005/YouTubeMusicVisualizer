$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSession, Windows.Media.Control, ContentType=WindowsRuntime]

function Await-WinRt($operation, [Type]$resultType) {
    $method = [System.WindowsRuntimeSystemExtensions].GetMethods() |
        Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 } |
        Select-Object -First 1
    $task = $method.MakeGenericMethod($resultType).Invoke($null, @($operation))
    try {
        $task.Wait()
    } catch {
        $inner = $_.Exception
        while ($inner.InnerException) { $inner = $inner.InnerException }
        throw $inner
    }
    return $task.Result
}

function Get-ChromeSession($manager) {
    $sessions = $manager.GetSessions()
    $playing = $sessions | Where-Object {
        $_.SourceAppUserModelId -match 'chrome' -and $_.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing'
    } | Select-Object -First 1
    if ($playing) { return $playing }
    return $sessions | Where-Object { $_.SourceAppUserModelId -match 'chrome' } | Select-Object -First 1
}

function Get-ArtworkBase64($thumbnail) {
    if ($null -eq $thumbnail) { return $null }
    try {
        [void][Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime]
        $stream = Await-WinRt $thumbnail.OpenReadAsync() ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
        $dotNetStream = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
        $memory = New-Object System.IO.MemoryStream
        $dotNetStream.CopyTo($memory)
        $result = [Convert]::ToBase64String($memory.ToArray())
        $memory.Dispose()
        $dotNetStream.Dispose()
        return $result
    } catch {
        return $null
    }
}

$manager = Await-WinRt ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

while (($line = [Console]::In.ReadLine()) -ne $null) {
    try {
        $request = $line | ConvertFrom-Json
        $session = Get-ChromeSession $manager
        if ($null -eq $session) { throw 'Chrome のメディアセッションが見つかりません' }

        switch ($request.command) {
            'state' {
                $properties = Await-WinRt ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
                $timeline = $session.GetTimelineProperties()
                $playback = $session.GetPlaybackInfo()
                @{
                    ok = $true
                    title = [string]$properties.Title
                    artist = [string]$properties.Artist
                    album = [string]$properties.AlbumTitle
                    artworkBase64 = Get-ArtworkBase64 $properties.Thumbnail
                    positionMillis = [long]$timeline.Position.TotalMilliseconds
                    durationMillis = [long]$timeline.EndTime.TotalMilliseconds
                    isPlaying = $playback.PlaybackStatus.ToString() -eq 'Playing'
                } | ConvertTo-Json -Compress
            }
            'toggle' {
                $ok = Await-WinRt ($session.TryTogglePlayPauseAsync()) ([bool])
                @{ ok = $ok } | ConvertTo-Json -Compress
            }
            'next' {
                $ok = Await-WinRt ($session.TrySkipNextAsync()) ([bool])
                @{ ok = $ok } | ConvertTo-Json -Compress
            }
            'previous' {
                $ok = Await-WinRt ($session.TrySkipPreviousAsync()) ([bool])
                @{ ok = $ok } | ConvertTo-Json -Compress
            }
            'seek' {
                $timeline = $session.GetTimelineProperties()
                $requested = [long](($timeline.Position.TotalMilliseconds + [long]$request.value) * 10000)
                $minimum = [long]($timeline.StartTime.TotalMilliseconds * 10000)
                $maximum = [long]($timeline.EndTime.TotalMilliseconds * 10000)
                $requested = [Math]::Max($minimum, [Math]::Min($maximum, $requested))
                $ok = Await-WinRt ($session.TryChangePlaybackPositionAsync($requested)) ([bool])
                @{ ok = $ok } | ConvertTo-Json -Compress
            }
            default { throw "Unknown command: $($request.command)" }
        }
    } catch {
        @{ ok = $false; error = $_.Exception.Message } | ConvertTo-Json -Compress
    }
}
