import { useEffect, useState } from 'react';
import { ArrowLeft, Save } from 'lucide-react';
import { Button } from '../ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../ui/tabs';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Badge } from '../ui/badge';
import { userController } from '../../controllers/UserController';
import { doctorController, type DoctorDTO, type WorkScheduleDTO } from '../../controllers/DoctorController';
import type { UserDTO } from '../../models/User';

interface AdminStaffDetailProps {
  staffId: string | null;
  onBack: () => void;
}

interface StaffForm {
  fullName: string;
  email: string;
  phone: string;
}

const roleLabels: Record<string, string> = {
  ADMIN: 'Quản trị viên',
  DOCTOR: 'Bác sĩ',
  RECEPTIONIST: 'Lễ tân',
  PHARMACIST: 'Dược sĩ',
  LAB_TECHNICIAN: 'Kỹ thuật viên',
  PATIENT: 'Bệnh nhân',
};

function getPrimaryRole(user: UserDTO): string {
  return user.primaryRole || user.roles?.[0] || '';
}

export function AdminStaffDetail({ staffId, onBack }: AdminStaffDetailProps) {
  const [staff, setStaff] = useState<UserDTO | null>(null);
  const [doctor, setDoctor] = useState<DoctorDTO | null>(null);
  const [workSchedules, setWorkSchedules] = useState<WorkScheduleDTO[]>([]);
  const [form, setForm] = useState<StaffForm>({ fullName: '', email: '', phone: '' });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;

    const loadStaff = async () => {
      if (!staffId) {
        setError('Không tìm thấy mã nhân viên.');
        setLoading(false);
        return;
      }

      setLoading(true);
      setError('');
      setDoctor(null);
      setWorkSchedules([]);

      try {
        const user = await userController.getById(staffId);
        if (cancelled) return;

        setStaff(user);
        setForm({
          fullName: user.fullName || '',
          email: user.email || '',
          phone: user.phone || '',
        });

        const roles = (user.roles || []).map((role) => role.toUpperCase());
        if (roles.includes('DOCTOR') || user.primaryRole?.toUpperCase() === 'DOCTOR') {
          const [doctorProfile, schedules] = await Promise.all([
            doctorController.getById(staffId).catch(() => null),
            doctorController.getWorkSchedulesByDoctor(staffId).catch(() => []),
          ]);
          if (!cancelled) {
            setDoctor(doctorProfile);
            setWorkSchedules(schedules);
          }
        }
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Không thể tải hồ sơ nhân viên.');
          setStaff(null);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    void loadStaff();
    return () => {
      cancelled = true;
    };
  }, [staffId]);

  const handleSave = async () => {
    if (!staffId || !staff) return;

    try {
      setSaving(true);
      const updated = await userController.update(staffId, {
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        phone: form.phone.trim(),
      });
      setStaff(updated);
      setForm({
        fullName: updated.fullName || '',
        email: updated.email || '',
        phone: updated.phone || '',
      });
      window.alert('Đã lưu thay đổi.');
    } catch (saveError) {
      window.alert(saveError instanceof Error ? saveError.message : 'Không thể lưu thay đổi.');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="p-6 text-neutral-text">Đang tải hồ sơ nhân viên...</div>;
  }

  if (error || !staff) {
    return (
      <div className="p-6 bg-[#fcfeff]">
        <Button variant="outline" size="sm" onClick={onBack} className="mb-4 rounded-[10px]">
          <ArrowLeft className="w-4 h-4 mr-2" />
          Quay lại danh sách
        </Button>
        <p role="alert" className="text-red-600">{error || 'Không tìm thấy nhân viên.'}</p>
      </div>
    );
  }

  const role = getPrimaryRole(staff).toUpperCase();
  const roleLabel = roleLabels[role] || role || 'Chưa xác định';
  const sortedSchedules = [...workSchedules].sort((a, b) =>
    `${a.workDate}T${a.startTime}`.localeCompare(`${b.workDate}T${b.startTime}`)
  );

  return (
    <div className="p-6 bg-[#fcfeff]">
      <div className="mb-6">
        <Button variant="outline" size="sm" onClick={onBack} className="mb-4 rounded-[10px] border-[#e8e8e8]">
          <ArrowLeft className="w-4 h-4 mr-2" />
          Quay lại danh sách
        </Button>
        <h1 className="text-[#01304e]">{staff.fullName || staff.username || 'Nhân viên'}</h1>
        <p className="text-[#333333]/60">{roleLabel}</p>
      </div>

      <Tabs defaultValue="profile">
        <TabsList>
          <TabsTrigger value="profile">Hồ sơ cá nhân</TabsTrigger>
          <TabsTrigger value="schedule">Cấu hình Lịch làm việc</TabsTrigger>
          <TabsTrigger value="performance">Hiệu suất</TabsTrigger>
        </TabsList>

        <TabsContent value="profile" className="mt-6">
          <Card className="rounded-[15px] border-[#e8e8e8] shadow-[0px_4px_12px_0px_rgba(159,166,175,0.08)]">
            <CardHeader>
              <CardTitle className="text-[#01304e]">Thông tin cá nhân</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-4 max-w-2xl">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <Label htmlFor="fullname">Họ và tên</Label>
                    <Input id="fullname" value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} className="rounded-[10px] border-[#e8e8e8]" />
                  </div>
                  <div>
                    <Label htmlFor="position">Vị trí</Label>
                    <Input id="position" value={roleLabel} readOnly className="rounded-[10px] border-[#e8e8e8]" />
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <Label htmlFor="phone">Số điện thoại</Label>
                    <Input id="phone" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} className="rounded-[10px] border-[#e8e8e8]" />
                  </div>
                  <div>
                    <Label htmlFor="email">Email</Label>
                    <Input id="email" type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} className="rounded-[10px] border-[#e8e8e8]" />
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <Label htmlFor="specialty">Chuyên môn</Label>
                    <Input
                      id="specialty"
                      value={doctor?.specializationCode || (role === 'DOCTOR' ? 'Chưa có hồ sơ bác sĩ' : '—')}
                      readOnly
                      className="rounded-[10px] border-[#e8e8e8]"
                    />
                  </div>
                  <div>
                    <Label htmlFor="license">Số giấy phép</Label>
                    <Input id="license" value={doctor?.licenseNumber || '—'} readOnly className="rounded-[10px] border-[#e8e8e8]" />
                  </div>
                </div>

                <div className="flex items-center gap-3">
                  <span className="text-sm">Vai trò hệ thống</span>
                  <Badge variant="outline">{(staff.roles || [role]).map((item) => roleLabels[item.toUpperCase()] || item).join(', ')}</Badge>
                  <Badge className={staff.isActive ? 'bg-green-600' : 'bg-gray-500'}>
                    {staff.isActive ? 'Đang hoạt động' : 'Đã khóa'}
                  </Badge>
                </div>

                <Button onClick={handleSave} disabled={saving} className="bg-[#3FB5FF] hover:bg-[#3FB5FF]/90 rounded-[15px] shadow-[0px_4px_4px_0px_rgba(0,0,0,0.25)]">
                  <Save className="w-4 h-4 mr-2" />
                  {saving ? 'Đang lưu...' : 'Lưu thay đổi'}
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="schedule" className="mt-6">
          <Card className="rounded-[15px] border-[#e8e8e8]">
            <CardHeader><CardTitle className="text-[#01304e]">Lịch làm việc</CardTitle></CardHeader>
            <CardContent className="text-neutral-text">
              {role !== 'DOCTOR' ? (
                'Nhân viên này không có lịch làm việc bác sĩ.'
              ) : sortedSchedules.length === 0 ? (
                'Bác sĩ chưa có ca làm việc được cấu hình.'
              ) : (
                <div className="space-y-2">
                  {sortedSchedules.map((schedule) => (
                    <div key={schedule.id} className="flex items-center justify-between rounded-lg border p-3">
                      <span>
                        {new Date(`${schedule.workDate}T00:00:00`).toLocaleDateString('vi-VN', {
                          weekday: 'long', year: 'numeric', month: '2-digit', day: '2-digit',
                        })}
                      </span>
                      <span>
                        {new Date(schedule.startTime).toLocaleTimeString('vi-VN', {
                          timeZone: 'Asia/Ho_Chi_Minh', hour: '2-digit', minute: '2-digit',
                        })}
                        {' – '}
                        {new Date(schedule.endTime).toLocaleTimeString('vi-VN', {
                          timeZone: 'Asia/Ho_Chi_Minh', hour: '2-digit', minute: '2-digit',
                        })}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="performance" className="mt-6">
          <Card className="rounded-[15px] border-[#e8e8e8]">
            <CardHeader><CardTitle className="text-[#01304e]">Hiệu suất</CardTitle></CardHeader>
            <CardContent className="text-neutral-text">
              Chưa có dữ liệu hiệu suất được tải cho nhân viên này.
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
