cask "hue-manager" do
  version "2026.06.29-6e83998"
  sha256 "df697f8451198ddb75e097160cd120418a3aac33df0673aa6fcfbda23bada156"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/460823954",
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
