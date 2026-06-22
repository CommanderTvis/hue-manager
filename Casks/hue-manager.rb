cask "hue-manager" do
  version "2026.06.22-8f423d6"
  sha256 "a7c5a0c6ee28682f482515b527b1fef80a957009588381c355f3a8489a750685"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/454880726",
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
