cask "hue-manager" do
  version "2026.05.26-3951b3c"
  sha256 "239c5fd8179cb0228008cf96dce4d27bb1919e26f817a947e630813feba4592e"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/430217745",
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
