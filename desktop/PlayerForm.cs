using LibVLCSharp.Shared;
using LibVLCSharp.WinForms;
using System.Drawing;
using System.Windows.Forms;

namespace MHPlayer.Windows;

public sealed class PlayerForm : Form
{
    private readonly LibVLC _engine;
    private readonly MediaPlayer _player;
    private readonly VideoView _video;
    private readonly Button _open = new() { Text = "Open media", Width = 110 };
    private readonly Button _play = new() { Text = "Play / Pause", Width = 110 };
    private readonly Button _stop = new() { Text = "Stop", Width = 65 };
    private readonly Button _fullscreen = new() { Text = "Fullscreen", Width = 90 };
    private readonly TrackBar _position = new() { Minimum = 0, Maximum = 1000, TickStyle = TickStyle.None, Dock = DockStyle.Fill };
    private readonly TrackBar _volume = new() { Minimum = 0, Maximum = 100, Value = 80, TickStyle = TickStyle.None, Width = 115 };
    private readonly Label _time = new() { Text = "00:00 / 00:00", AutoSize = true, ForeColor = Color.White, Padding = new Padding(5, 9, 5, 0) };
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 400 };
    private readonly List<string> _playlist = new();
    private int _index = -1;
    private bool _seeking;
    private bool _full;
    private FormBorderStyle _previousBorder;
    private FormWindowState _previousState;
    private Rectangle _previousBounds;

    public PlayerForm(string[] args)
    {
        Text = "MH Player — Windows";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(1040, 650);
        MinimumSize = new Size(580, 350);
        BackColor = Color.FromArgb(19, 22, 30);
        ForeColor = Color.White;
        AllowDrop = true;
        KeyPreview = true;

        Core.Initialize();
        _engine = new LibVLC("--no-video-title-show");
        _player = new MediaPlayer(_engine) { Volume = 80 };
        _video = new VideoView { MediaPlayer = _player, Dock = DockStyle.Fill, BackColor = Color.Black };

        var controls = new FlowLayoutPanel { Dock = DockStyle.Fill, Height = 48, FlowDirection = FlowDirection.LeftToRight, WrapContents = false, AutoScroll = true, BackColor = Color.FromArgb(31, 35, 46), Padding = new Padding(8, 7, 4, 4) };
        foreach (var button in new[] { _open, _play, _stop, _fullscreen })
        {
            button.Height = 30;
            button.FlatStyle = FlatStyle.Flat;
            button.ForeColor = Color.White;
            button.BackColor = Color.FromArgb(54, 61, 77);
            controls.Controls.Add(button);
        }
        controls.Controls.Add(_time);
        controls.Controls.Add(new Label { Text = "Volume", AutoSize = true, Padding = new Padding(8, 9, 0, 0), ForeColor = Color.White });
        controls.Controls.Add(_volume);

        var seek = new Panel { Dock = DockStyle.Fill, Height = 30, Padding = new Padding(10, 0, 10, 0), BackColor = Color.FromArgb(31, 35, 46) };
        seek.Controls.Add(_position);
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 3, ColumnCount = 1, Margin = Padding.Empty, Padding = Padding.Empty };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 49));
        layout.Controls.Add(_video, 0, 0);
        layout.Controls.Add(seek, 0, 1);
        layout.Controls.Add(controls, 0, 2);
        Controls.Add(layout);

        _open.Click += (_, _) => OpenFiles();
        _play.Click += (_, _) => TogglePlay();
        _stop.Click += (_, _) => _player.Stop();
        _fullscreen.Click += (_, _) => ToggleFullscreen();
        _volume.Scroll += (_, _) => _player.Volume = _volume.Value;
        _position.MouseDown += (_, _) => _seeking = true;
        _position.MouseUp += (_, _) => { if (_player.Length > 0) _player.Time = (long)(_position.Value / 1000.0 * _player.Length); _seeking = false; };
        _timer.Tick += (_, _) => RefreshPosition();
        _timer.Start();
        _player.EndReached += (_, _) => { if (!IsDisposed && IsHandleCreated) BeginInvoke(new Action(Next)); };
        DragEnter += (_, e) => { if (e.Data?.GetDataPresent(DataFormats.FileDrop) == true) e.Effect = DragDropEffects.Copy; };
        DragDrop += (_, e) => { if (e.Data?.GetData(DataFormats.FileDrop) is string[] paths) AddFiles(paths); };
        KeyDown += OnShortcut;
        FormClosed += (_, _) => { _timer.Stop(); _video.MediaPlayer = null; _player.Dispose(); _engine.Dispose(); };
        if (args.Length > 0) Shown += (_, _) => AddFiles(args);
    }

    private void OpenFiles()
    {
        using var dialog = new OpenFileDialog
        {
            Title = "Open video or audio",
            Filter = "Media files|*.mp4;*.mkv;*.avi;*.mov;*.webm;*.mp3;*.flac;*.wav;*.m4a;*.ogg;*.ts;*.wmv;*.aac|All files|*.*",
            Multiselect = true
        };
        if (dialog.ShowDialog(this) == DialogResult.OK) AddFiles(dialog.FileNames);
    }

    private void AddFiles(IEnumerable<string> paths)
    {
        var added = paths.Where(File.Exists).ToList();
        if (added.Count == 0) return;
        _playlist.AddRange(added);
        PlayAt(_playlist.Count - added.Count);
    }

    private void PlayAt(int index)
    {
        if (index < 0 || index >= _playlist.Count) return;
        _index = index;
        var path = _playlist[index];
        using var media = new Media(_engine, new Uri(Path.GetFullPath(path)));
        _player.Play(media);
        Text = $"MH Player — {Path.GetFileName(path)}";
    }

    private void Next()
    {
        if (_index + 1 < _playlist.Count) PlayAt(_index + 1);
    }

    private void TogglePlay()
    {
        if (_player.IsPlaying) _player.Pause();
        else if (_index >= 0) _player.Play();
        else OpenFiles();
    }

    private void RefreshPosition()
    {
        if (IsDisposed || _seeking) return;
        if (_player.Length > 0)
        {
            _position.Value = Math.Clamp((int)(_player.Time * 1000.0 / _player.Length), 0, 1000);
            _time.Text = $"{FormatTime(_player.Time)} / {FormatTime(_player.Length)}";
        }
    }

    private static string FormatTime(long ms) => TimeSpan.FromMilliseconds(Math.Max(0, ms)).ToString(@"hh\:mm\:ss");

    private void ToggleFullscreen()
    {
        if (!_full)
        {
            _previousBorder = FormBorderStyle;
            _previousState = WindowState;
            _previousBounds = Bounds;
            FormBorderStyle = FormBorderStyle.None;
            WindowState = FormWindowState.Maximized;
        }
        else
        {
            WindowState = FormWindowState.Normal;
            FormBorderStyle = _previousBorder;
            Bounds = _previousBounds;
            WindowState = _previousState;
        }
        _full = !_full;
    }

    private void OnShortcut(object? sender, KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Space) TogglePlay();
        else if (e.KeyCode == Keys.F11 || (e.KeyCode == Keys.Escape && _full)) ToggleFullscreen();
        else if (e.KeyCode == Keys.Right && _player.Length > 0) _player.Time = Math.Min(_player.Length, _player.Time + 10000);
        else if (e.KeyCode == Keys.Left && _player.Length > 0) _player.Time = Math.Max(0, _player.Time - 10000);
        else if (e.Control && e.KeyCode == Keys.O) OpenFiles();
        else return;
        e.Handled = true;
    }
}