cask "hue-manager" do
  version "2026.06.23-7f99947"
  sha256 "2f8dd7c2e94c834acb408daa5d0099af1c38f0ef692eb48aebf86d71fe3ee961"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455763023",
      header: [
        "Authorization: token #{ENV.fetch("HOMEBREW_GITHUB_API_TOKEN", "")}",
        "Accept: application/octet-stream",
      ]
  name "Hue Manager"
  desc "Philips Hue lamp management desktop application"
  homepage "https://github.com/CommanderTvis/hue-manager"

  depends_on macos: ">= :ventura"

  app "Hue Manager.app"

  zap trash: [
    "~/Library/Preferences/io.github.commandertvis.huemanager.plist",
    "~/Library/Application Support/io.github.commandertvis.huemanager",
  ]
end
